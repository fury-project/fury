package org.apache.fury.annotation;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface FieldInfo {

    /**
     * Whether to track reference.
     */
    boolean trackingRef() default false;
    /**
     * Whether field is nullable.
     */
    boolean nullable() default true;
}
