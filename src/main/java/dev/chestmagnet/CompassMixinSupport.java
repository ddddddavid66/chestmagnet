package dev.chestmagnet;
/** 资源存在性检查不初始化可选模组类，防止过早加载。 */
public final class CompassMixinSupport {
 public static boolean present(String name){return CompassMixinSupport.class.getClassLoader().getResource(name.replace('.','/')+".class")!=null;}
}
