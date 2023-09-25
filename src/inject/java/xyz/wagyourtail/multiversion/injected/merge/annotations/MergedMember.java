package xyz.wagyourtail.multiversion.injected.merge.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface MergedMember {

    String name() default "";

    String[] versions();

    boolean synthetic() default false;

    Access[] access() default {};

}
