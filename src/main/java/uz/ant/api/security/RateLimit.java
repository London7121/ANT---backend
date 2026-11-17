package uz.ant.api.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** IP boshiga `windowSeconds` ichida `max` ta so'rov; oshsa 429 `too_many_requests`. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RateLimit {
    int max();
    int windowSeconds();
}
