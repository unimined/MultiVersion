package xyz.wagyourtail.multiversion.injected.split.annotations;

import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.METHOD, ElementType.FIELD, ElementType.TYPE })
@Repeatable(Modify.ReplaceHolder.class)
public @interface Modify {

    String[] versions();

    Ref ref() default @Ref(Void.class);

    boolean field() default false;

    @Retention(RetentionPolicy.CLASS)
    @Target({ ElementType.METHOD, ElementType.FIELD, ElementType.TYPE })
    @interface ReplaceHolder {
        Modify[] value();
    }

}
