package io.github.fuoins.reactionsorter.core;

/**
 * Package names the module is willing to hook. Mirrors res/values/arrays.xml (the LSPosed
 * recommended scope) and is checked again at runtime so a manually widened scope cannot
 * make the module touch unrelated apps.
 */
public final class Scope {

    private static final String[] PACKAGES = {
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.plus",
            "tw.nekomimi.nekogram",
            "uz.unnarsx.cherrygram",
            "nu.gpu.nagram",
            "nu.gpu.nagramx",
            "xyz.nextalone.nagram",
            "org.forkgram.messenger",
            "org.forkgram.classic",
            "it.belloworld.mercurygram",
            "momo.gram",
            "ir.ilmili.telegraph",
            "ua.entaytion.entinygram"
    };

    private Scope() {}

    public static boolean isSupported(String packageName) {
        if (packageName == null) return false;
        for (String p : PACKAGES) {
            if (p.equals(packageName)) return true;
        }
        return false;
    }
}
