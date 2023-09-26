package xyz.wagyourtail.multiversion.injected.split.annotations;

import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.METHOD, ElementType.FIELD, ElementType.TYPE })
@Repeatable(Stub.StubHolder.class)
public @interface Stub {

    String[] versions();

    Ref ref() default @Ref("");

    boolean field() default false;

    @Retention(RetentionPolicy.CLASS)
    @Target({ ElementType.METHOD, ElementType.FIELD, ElementType.TYPE })
    @interface StubHolder {
        Stub[] value();
    }

}
