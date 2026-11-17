package uz.ant.api.common;

import org.springframework.http.HttpStatus;

/** Frontend kutadigan xato shakli: `{ "error": "kod", "detail"?: ... }`. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final transient Object detail;
    private final transient java.util.Map<String, Object> extra;

    public ApiException(HttpStatus status, String code, Object detail) {
        this(status, code, detail, java.util.Map.of());
    }

    private ApiException(HttpStatus status, String code, Object detail, java.util.Map<String, Object> extra) {
        super(code);
        this.status = status;
        this.code = code;
        this.detail = detail;
        this.extra = extra;
    }

    /** 403 `{ error: 'forbidden', permission }` */
    public static ApiException forbiddenPermission(String permission) {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", null, java.util.Map.of("permission", permission));
    }

    public ApiException(HttpStatus status, String code) {
        this(status, code, null);
    }

    public static ApiException badRequest(String code) { return new ApiException(HttpStatus.BAD_REQUEST, code); }
    public static ApiException unauthorized() { return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized"); }
    public static ApiException forbidden(String code) { return new ApiException(HttpStatus.FORBIDDEN, code); }
    public static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "not_found"); }
    public static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT, code); }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Object detail() { return detail; }
    public java.util.Map<String, Object> extra() { return extra; }
}
