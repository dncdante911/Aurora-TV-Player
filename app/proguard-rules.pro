# jcifs-ng loads protocol and crypto classes reflectively and ships no consumer rules.
-keep class jcifs.** { *; }
-dontwarn jcifs.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.servlet.**
-dontwarn org.slf4j.**
