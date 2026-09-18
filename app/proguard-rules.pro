# JSch пользуется отражением (JCE, KEX-фабрики) — при включённой минификации
# классы библиотеки нужно сохранять целиком.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-keep class org.ietf.jgss.** { *; }
-dontwarn org.ietf.jgss.**
