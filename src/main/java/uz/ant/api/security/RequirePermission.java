package uz.ant.api.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Endpoint uchun xodimda shu ruxsat (`modul.amal`) bo'lishi shart; aks holda 403 `{error:'forbidden', permission}`. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequirePermission {
    String value();
}
