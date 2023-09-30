package xyz.wagyourtail.multiversion.injected.split.annotations;

import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.METHOD })
@Repeatable(Modify.ModifyHolder.class)
public @interface Modify {

    String[] versions();

    Ref ref() default @Ref;

    boolean field() default false;

    @Retention(RetentionPolicy.CLASS)
    @Target({ ElementType.METHOD })
    @interface ModifyHolder {
        Modify[] value();
    }

}
