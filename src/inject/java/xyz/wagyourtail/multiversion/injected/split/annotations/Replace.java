package xyz.wagyourtail.multiversion.injected.split.annotations;

import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.METHOD })
@Repeatable(Replace.ReplaceHolder.class)
public @interface Replace {

    String[] versions();

    Ref ref() default @Ref;

    boolean field() default false;

    @Retention(RetentionPolicy.CLASS)
    @Target({ ElementType.METHOD })
    @interface ReplaceHolder {
        Replace[] value();
    }

}
