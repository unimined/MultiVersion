package xyz.wagyourtail.multiversion.injected.split.annotations;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.METHOD })
@Repeatable(Modify.ModifyHolder.class)
public @interface Modify {
    Class<?>[] PARAMS = {MethodNode.class, int.class, String.class, ClassNode.class };

    String[] versions();

    Ref ref();

    boolean field() default false;

    @Retention(RetentionPolicy.CLASS)
    @Target({ ElementType.METHOD })
    @interface ModifyHolder {
        Modify[] value();
    }

}
