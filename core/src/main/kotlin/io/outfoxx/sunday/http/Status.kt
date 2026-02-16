package io.outfoxx.sunday.http

import com.fasterxml.jackson.annotation.JsonCreator

data class Status(
  val code: Int,
  val reasonPhrase: String? = null,
) {

  companion object {

    // 100 - Informational

    val Continue = Status(100, "Continue")
    val SwitchingProtocols = Status(101, "Switching Protocols")
    val Processing = Status(102, "Processing")
    val EarlyHints = Status(103, "Early Hints")

    // 200 - Successful

    val Ok = Status(200, "OK")
    val Created = Status(201, "Created")
    val Accepted = Status(202, "Accepted")
    val NonAuthoritativeInformation = Status(203, "Non-Authoritative Information")
    val NoContent = Status(204, "No Content")
    val ResetContent = Status(205, "Reset Content")
    val PartialContent = Status(206, "Partial Content")
    val MultiStatus = Status(207, "Multi-Status")
    val AlreadyReported = Status(208, "Already Reported")
    val ImUsed = Status(226, "IM Used")

    // 300 - Redirection

    val MultipleChoices = Status(300, "Multiple Choices")
    val MovedPermanently = Status(301, "Moved Permanently")
    val MovedTemporarily = Status(302, "Found")
    val SeeOther = Status(303, "See Other")
    val NotModified = Status(304, "Not Modified")
    val UseProxy = Status(305, "Use Proxy")
    val Unused_306 = Status(306, "Unused") // Unused/Reserved
    val TemporaryRedirect = Status(307, "Temporary Redirect")
    val PermanentRedirect = Status(308, "Permanent Redirect")

    // 400 - Client Error

    val BadRequest = Status(400, "Bad Request")
    val Unauthorized = Status(401, "Unauthorized")
    val PaymentRequired = Status(402, "Payment Required")
    val Forbidden = Status(403, "Forbidden")
    val NotFound = Status(404, "Not Found")
    val MethodNotAllowed = Status(405, "Method Not Allowed")
    val NotAcceptable = Status(406, "Not Acceptable")
    val ProxyAuthenticationRequired = Status(407, "Proxy Authentication Required")
    val RequestTimeout = Status(408, "Request Timeout")
    val Conflict = Status(409, "Conflict")
    val Gone = Status(410, "Gone")
    val LengthRequired = Status(411, "Length Required")
    val PreconditionFailed = Status(412, "Precondition Failed")
    val ContentTooLarge = Status(413, "Content Too Large")
    val UriTooLong = Status(414, "URI Too Long")
    val UnsupportedMediaType = Status(415, "Unsupported Media Type")
    val RangeNotSatisfiable = Status(416, "Range Not Satisfiable")
    val ExpectationFailed = Status(417, "Expectation Failed")
    val Unused_418 = Status(418, "Unused")

    @Deprecated("RFC 9110 registers 418 as unused; use Unused_418")
    val ImATeapot = Status(418, "I'm a teapot")
    val MisdirectedRequest = Status(421, "Misdirected Request")
    val UnprocessableContent = Status(422, "Unprocessable Content")
    val Locked = Status(423, "Locked")
    val FailedDependency = Status(424, "Failed Dependency")
    val TooEarly = Status(425, "Too Early")
    val UpgradeRequired = Status(426, "Upgrade Required")
    val PreconditionRequired = Status(428, "Precondition Required")
    val TooManyRequests = Status(429, "Too Many Requests")
    val RequestHeaderFieldsTooLarge = Status(431, "Request Header Fields Too Large")
    val UnavailableForLegalReasons = Status(451, "Unavailable For Legal Reasons")

    // 500 - Server Error

    val InternalServerError = Status(500, "Internal Server Error")
    val NotImplemented = Status(501, "Not Implemented")
    val BadGateway = Status(502, "Bad Gateway")
    val ServiceUnavailable = Status(503, "Service Unavailable")
    val GatewayTimeout = Status(504, "Gateway Timeout")
    val HttpVersionNotSupported = Status(505, "HTTP Version Not Supported")
    val VariantAlsoNegotiates = Status(506, "Variant Also Negotiates")
    val InsufficientStorage = Status(507, "Insufficient Storage")
    val LoopDetected = Status(508, "Loop Detected")
    val NotExtended = Status(510, "Not Extended")
    val NetworkAuthenticationRequired = Status(511, "Network Authentication Required")

    val entries =
      listOf(
        // 100 - Informational
        Continue,
        SwitchingProtocols,
        Processing,
        EarlyHints,
        // 200 - Successful
        Ok,
        Created,
        Accepted,
        NonAuthoritativeInformation,
        NoContent,
        ResetContent,
        PartialContent,
        MultiStatus,
        AlreadyReported,
        ImUsed,
        // 300 - Redirection
        MultipleChoices,
        MovedPermanently,
        MovedTemporarily,
        SeeOther,
        NotModified,
        UseProxy,
        Unused_306,
        TemporaryRedirect,
        PermanentRedirect,
        // 400 - Client Error
        BadRequest,
        Unauthorized,
        PaymentRequired,
        Forbidden,
        NotFound,
        MethodNotAllowed,
        NotAcceptable,
        ProxyAuthenticationRequired,
        RequestTimeout,
        Conflict,
        Gone,
        LengthRequired,
        PreconditionFailed,
        ContentTooLarge,
        UriTooLong,
        UnsupportedMediaType,
        RangeNotSatisfiable,
        ExpectationFailed,
        Unused_418,
        MisdirectedRequest,
        UnprocessableContent,
        Locked,
        FailedDependency,
        TooEarly,
        UpgradeRequired,
        PreconditionRequired,
        TooManyRequests,
        RequestHeaderFieldsTooLarge,
        UnavailableForLegalReasons,
        // 500 - Server Error
        InternalServerError,
        NotImplemented,
        BadGateway,
        ServiceUnavailable,
        GatewayTimeout,
        HttpVersionNotSupported,
        VariantAlsoNegotiates,
        InsufficientStorage,
        LoopDetected,
        NotExtended,
        NetworkAuthenticationRequired,
      )

    @JvmStatic
    fun valueOf(code: Int) = entries.find { it.code == code } ?: Status(code)

    @JsonCreator
    @JvmStatic
    fun valueOf(reasonPhraseOrCode: Any): Status? =
      when (reasonPhraseOrCode) {
        is Int -> valueOf(reasonPhraseOrCode)
        is String -> {
          reasonPhraseOrCode.toIntOrNull()?.let { return valueOf(it) }
          entries.find { it.reasonPhrase?.equals(reasonPhraseOrCode, ignoreCase = true) ?: false }
        }
        else -> null
      }

    @JvmStatic
    fun valueOf(
      code: Int,
      reasonPhrase: String?,
    ) = if (reasonPhrase.isNullOrBlank()) valueOf(code) else Status(code, reasonPhrase)
  }
}
