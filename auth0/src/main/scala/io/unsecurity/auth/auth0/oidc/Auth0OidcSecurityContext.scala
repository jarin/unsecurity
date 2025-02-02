package io.unsecurity
package auth.auth0
package oidc

import java.net.URI
import java.security.interfaces.RSAPublicKey

import cats.data.EitherT
import cats.effect.{Resource, Sync}
import cats.syntax.functor._
import com.auth0.client.auth.AuthAPI
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import io.circe.parser.{decode => cDecode}
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import io.unsecurity.auth.auth0.oidc.Jwt.JwtHeader
import no.scalabin.http4s.directives.Directive
import org.http4s._
import org.http4s.client.Client
import org.slf4j.{Logger, LoggerFactory}
import scodec.bits.BitVector

class Auth0OidcSecurityContext[F[_]: Sync, U](val authConfig: AuthConfig,
                                              val sessionStore: SessionStore[F, OidcAuthenticatedUser],
                                              client: Resource[F, Client[F]],
                                              lookup: OidcAuthenticatedUser => F[Option[U]])
    extends SecurityContext[F, OidcAuthenticatedUser, U]
    with UnsecurityOps[F] {
  val log: Logger = LoggerFactory.getLogger(classOf[Auth0OidcSecurityContext[F, U]])

  import responses._

  override def transformUser(u: OidcAuthenticatedUser): F[Option[U]] = lookup(u)

  override def authenticate: Directive[F, OidcAuthenticatedUser] = {
    log.trace("trying to authenticate")
    for {
      sessionCookie <- sessionCookie
      userProfile   <- userSession(sessionCookie)
    } yield {
      userProfile
    }
  }

  override def xsrfCheck: Directive[F, String] = {
    for {
      xForwardedFor   <- request.header("X-Forwarded-For")
      xsrfHeader      <- xsrfHeader(xForwardedFor.map(_.value))
      xsrfCookie      <- xsrfCookie()
      validatedHeader <- validateXsrf(xsrfHeader, xsrfCookie, xForwardedFor.map(_.value))
    } yield {
      validatedHeader
    }
  }

  def validateXsrf(xsrfHeader: String, xsrfCookievalue: String, xForwardedFor: Option[String]): Directive[F, String] = {
    Directive
      .success(xsrfHeader)
      .filter(hdr =>
        (xsrfCookievalue == hdr).orF(
          Sync[F].delay {
            log.error(
              s"XsrfCookie does not match Xsrf header, possible CSRF-Attack! X-Forwarded-For: ${xForwardedFor.getOrElse("")}"
            )
            ResponseJson("xsrf check failed", Status.BadRequest)
          }
      ))
  }

  def xsrfHeader(xForwardedFor: Option[String]): Directive[F, String] = {
    for {
      header <- request.header("x-xsrf-token")
      xsrfToken <- Directive.getOrElseF(
                    header,
                    Sync[F].delay {
                      log.error("No x-xsrf-token header, possible CSRF-attack!")
                      ResponseJson(
                        s"No x-xsrf-token header found. X-Forwarded-For: ${xForwardedFor.getOrElse("")}",
                        Status.BadRequest
                      )
                    }
                  )
    } yield {
      xsrfToken.value
    }
  }

  def xsrfCookie(): Directive[F, String] = {
    for {
      maybeCookie   <- request.cookie("xsrf-token")
      xForwardedfor <- request.header("X-Forwarded-For")
      xsrfCookie <- Directive.getOrElseF(
                     maybeCookie,
                     Sync[F].delay {
                       log.error(
                         s"No xsrf-cookie, possible CSRF-Attack from ${xForwardedfor.map(_.value).getOrElse("")}")
                       ResponseJson("No xsrf-token cookie found", Status.BadRequest)
                     }
                   )
    } yield {
      xsrfCookie.content
    }
  }

  def userSession(cookie: RequestCookie): Directive[F, OidcAuthenticatedUser] = {
    Directive.getOrElseF(
      sessionStore.getSession(cookie.content),
      Sync[F].delay {
        log.warn("Could not extract user profile: {}, session timed out")
        unauthorizedResponse("Could not extract user profile from the cookie")
      }
    )
  }

  def createAuth0Url(state: String, auth0CallbackUrl: URI): String = {
    new AuthAPI(authConfig.authDomain, authConfig.clientId, authConfig.clientSecret)
      .authorizeUrl(auth0CallbackUrl.toString)
      .withScope("openid profile email")
      .withState(state)
      .withResponseType("code")
      .build()
  }

  def validateState(stateCookie: RequestCookie, state: String, xForwardedFor: Option[String]): Directive[F, State] = {
    for {
      sessionState <- Directive.getOrElseF(
                       sessionStore.getState(stateCookie.content),
                       Sync[F].delay {
                         log.error(
                           s"Invalid state, possible CSRF-attack on login. X-Forwarded-For: ${xForwardedFor.getOrElse("")}")
                         ResponseJson("Invalid state, possible csrf-attack", Status.BadRequest)
                       }
                     )
      if (state == sessionState.state).orF(
        Sync[F].delay {
          log.error(
            s"State values does not match, possible XSRF-attack! X-Forwarded-For: ${xForwardedFor.getOrElse("")} "
          )
          ResponseJson("Illegal state value", Status.BadRequest)
        }
      )
    } yield sessionState
  }

  def isReturnUrlWhitelisted(uri: URI): Boolean = {
    authConfig.returnToUrlDomainWhitelist.contains(uri.getHost)
  }

  def sessionCookie: Directive[F, RequestCookie] = {
    for {
      maybeCookie <- request.cookie(authConfig.cookieName)
      cookie <- Directive.getOrElseF(
                 maybeCookie,
                 Sync[F].delay(ResponseJson("Session cookie not found. Please login", Status.Unauthorized)))

    } yield {
      cookie
    }
  }

  def fetchTokenFromAuth0(code: String, auth0CallbackUrl: URI): Directive[F, TokenResponse] = {
    val tokenUrl      = Uri.unsafeFromString(s"https://${authConfig.authDomain}/oauth/token")
    val jsonMediaType = MediaType.application.json
    val payload = TokenRequest(grantType = "authorization_code",
                               clientId = authConfig.clientId,
                               clientSecret = authConfig.clientSecret,
                               code = code,
                               redirectUri = auth0CallbackUrl)

    implicit val jsonEncoder: EntityEncoder[F, TokenRequest] = org.http4s.circe.jsonEncoderOf[F, TokenRequest]

    val req = Request[F](
      method = Method.POST,
      uri = tokenUrl
    ).withContentType(org.http4s.headers.`Content-Type`(jsonMediaType, Charset.`UTF-8`))
      .withEntity(payload)

    val res = client.use { c =>
      c.fetch(req)(res => res.attemptAs[String].fold(_ => res.status -> None, s => res.status -> Some(s)))
    }

    EitherT(
      res
        .map {
          case (status, maybeBody) =>
            if (status == Status.Ok) {
              maybeBody
                .toRight(Json.obj("msg" := "No data received from IdP"))
                .flatMap(cDecode[TokenResponse](_).left.map { e =>
                  log.error("Error parsing token from auth0 {}. Payload : {}", List(e, maybeBody.getOrElse("")): _*)
                  Json.obj("msg" := "Error parsing token from auth0")
                })
            } else {
              Left(Json.obj("msg" := "Invalid response from IDP"))
            }
        })
      .toSuccess(failure => Directive.failure(ResponseJson(failure, Status.InternalServerError)))
  }

  def verifyTokenAndGetOidcUser(tokenResponse: TokenResponse,
                                jwkProvider: JwkProvider): Directive[F, OidcAuthenticatedUser] = {
    def decodeBase64(value: String): String = BitVector.fromValidBase64(value).decodeUtf8.getOrElse("")

    val decodedJwt: DecodedJWT                         = JWT.decode(tokenResponse.idToken)
    val decodedHeaderString                            = decodeBase64(decodedJwt.getHeader)
    val decodedEitherHeader: Either[String, JwtHeader] = cDecode[JwtHeader](decodedHeaderString).left.map(_.getMessage)

    val eitherUser: EitherT[F, String, OidcAuthenticatedUser] = for {
      header    <- EitherT.fromEither(decodedEitherHeader)
      publicKey <- EitherT.liftF(Sync[F].delay(jwkProvider.get(header.kid).getPublicKey.asInstanceOf[RSAPublicKey]))
      alg       = Algorithm.RSA256(TokenVerifier.createPublicKeyProvider(publicKey))
      oidcUser <- EitherT.fromEither(
                   TokenVerifier
                     .validateIdToken(alg, authConfig.authDomain, authConfig.clientId, tokenResponse.idToken))
    } yield {
      oidcUser
    }

    eitherUser.toSuccess(failure => Directive.failure(ResponseJson(failure, Status.InternalServerError)))
  }

  def randomString(lengthInBytes: Int)(implicit randomProvider: RandomProvider[F]): F[String] = {
    randomProvider.nextBytes(lengthInBytes).map(_.toHex)
  }

  object Cookies {

    object Keys {
      val STATE: String        = "statecookie"
      val K_SESSION_ID: String = authConfig.cookieName
      val XSRF: String         = "xsrf-token"
    }

    def createXsrfCookie(secureCookie: Boolean): F[ResponseCookie] = {
      val xsrfToken = randomString(32)
      xsrfToken.map { xsrf =>
        log.trace("xsrfToken: {}", xsrf)
        ResponseCookie(
          name = Keys.XSRF,
          content = xsrf,
          secure = secureCookie,
          httpOnly = false,
          path = Some("/"),
          maxAge = Some(authConfig.sessionCookieTtl.toSeconds),
          extension = Some("SameSite=Lax")
        )
      }
    }

    def createSessionCookie(secureCookie: Boolean): F[ResponseCookie] = {
      val cookie = randomString(64)
      cookie.map { sessionId =>
        ResponseCookie(
          name = Keys.K_SESSION_ID,
          content = sessionId,
          secure = secureCookie,
          path = Some("/"),
          httpOnly = true,
          maxAge = Some(authConfig.sessionCookieTtl.toSeconds),
          extension = Some("SameSite=Strict")
        )
      }
    }

    def createStateCookie(secureCookie: Boolean): F[ResponseCookie] = {
      randomString(16).map { state =>
        log.trace("stateCookieRef: {}", state)
        ResponseCookie(name = Cookies.Keys.STATE, content = state, secure = secureCookie)
      }
    }
  }

}

case class TokenRequest(grantType: String, clientId: String, clientSecret: String, code: String, redirectUri: URI)

object TokenRequest {
  implicit val tokenRequestEncoder: Encoder[TokenRequest] = Encoder { tr =>
    Json.obj(
      "grant_type" := tr.grantType,
      "client_id" := tr.clientId,
      "client_secret" := tr.clientSecret,
      "code" := tr.code,
      "redirect_uri" := tr.redirectUri.toString
    )
  }
}

case class TokenResponse(accessToken: String, expiresIn: Long, idToken: String, tokenType: String)

object TokenResponse {
  implicit val tokenResponseDecoder: Decoder[TokenResponse] = Decoder { c =>
    for {
      accessToken <- c.downField("access_token").as[String]
      expiresIn   <- c.downField("expires_in").as[Long]
      idToken     <- c.downField("id_token").as[String]
      tokenType   <- c.downField("token_type").as[String]
    } yield {
      TokenResponse(
        accessToken,
        expiresIn,
        idToken,
        tokenType
      )
    }
  }
}
