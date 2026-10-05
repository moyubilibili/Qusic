package com.qusic.app;

import android.graphics.Color;

/**
 * MD3 颜色令牌（Material Design 3 color roles）。
 *
 * <p>由一颗种子色推导出完整的亮色/暗色配色：13 个色调 → 5 组角色
 * （Primary / Secondary / Tertiary / Neutral / NeutralVariant / Error）。
 *
 * <p>{@link #of(int, boolean)} 是唯一的构造入口；所有字段都是 final，
 * 换肤时整体替换实例，避免出现半新半旧的中间态配色。
 */
public final class Tokens {

    public final boolean dark;
    public final int seed;

    // Primary
    public final int primary, onPrimary, primaryContainer, onPrimaryContainer, primaryFixedDim;
    // Secondary
    public final int secondary, onSecondary, secondaryContainer, onSecondaryContainer;
    // Tertiary
    public final int tertiary, onTertiary, tertiaryContainer, onTertiaryContainer;
    // Error
    public final int error, onError, errorContainer, onErrorContainer;
    // Surfaces
    public final int background, onBackground;
    public final int surface, onSurface, surfaceVariant, onSurfaceVariant;
    public final int surfaceDim, surfaceBright;
    public final int surfaceContainerLowest, surfaceContainerLow, surfaceContainer,
            surfaceContainerHigh, surfaceContainerHighest;
    public final int outline, outlineVariant, scrim, inverseSurface, inverseOnSurface, inversePrimary;

    /** 阴影色调（MD3 阴影用 primary 着色，形成高级的紫灰感） */
    public final int shadowTint;

    /** 内部构建器：避免超长位置参数构造器带来的顺序错误 */
    private static final class B {
        boolean dark; int seed;
        int primary, onPrimary, primaryContainer, onPrimaryContainer, primaryFixedDim;
        int secondary, onSecondary, secondaryContainer, onSecondaryContainer;
        int tertiary, onTertiary, tertiaryContainer, onTertiaryContainer;
        int error, onError, errorContainer, onErrorContainer;
        int background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant;
        int surfaceDim, surfaceBright, surfaceContainerLowest, surfaceContainerLow,
                surfaceContainer, surfaceContainerHigh, surfaceContainerHighest;
        int outline, outlineVariant, scrim, inverseSurface, inverseOnSurface, inversePrimary;
        int shadowTint;

        Tokens build() {
            return new Tokens(this);
        }
    }

    private Tokens(B b) {
        this.dark = b.dark;
        this.seed = b.seed;
        this.primary = b.primary; this.onPrimary = b.onPrimary;
        this.primaryContainer = b.primaryContainer; this.onPrimaryContainer = b.onPrimaryContainer;
        this.primaryFixedDim = b.primaryFixedDim;
        this.secondary = b.secondary; this.onSecondary = b.onSecondary;
        this.secondaryContainer = b.secondaryContainer; this.onSecondaryContainer = b.onSecondaryContainer;
        this.tertiary = b.tertiary; this.onTertiary = b.onTertiary;
        this.tertiaryContainer = b.tertiaryContainer; this.onTertiaryContainer = b.onTertiaryContainer;
        this.error = b.error; this.onError = b.onError;
        this.errorContainer = b.errorContainer; this.onErrorContainer = b.onErrorContainer;
        this.background = b.background; this.onBackground = b.onBackground;
        this.surface = b.surface; this.onSurface = b.onSurface;
        this.surfaceVariant = b.surfaceVariant; this.onSurfaceVariant = b.onSurfaceVariant;
        this.surfaceDim = b.surfaceDim; this.surfaceBright = b.surfaceBright;
        this.surfaceContainerLowest = b.surfaceContainerLowest;
        this.surfaceContainerLow = b.surfaceContainerLow;
        this.surfaceContainer = b.surfaceContainer;
        this.surfaceContainerHigh = b.surfaceContainerHigh;
        this.surfaceContainerHighest = b.surfaceContainerHighest;
        this.outline = b.outline; this.outlineVariant = b.outlineVariant; this.scrim = b.scrim;
        this.inverseSurface = b.inverseSurface; this.inverseOnSurface = b.inverseOnSurface;
        this.inversePrimary = b.inversePrimary;
        this.shadowTint = b.shadowTint;
    }

    public static Tokens of(int seed) { return of(seed, false); }

    /**
     * 由种子色推导整套配色。
     *
     * <p>neutrals 会带上一点点种子色相（MD3 的精髓：界面不是纯灰，而是
     * 极低饱和度的同色系），这让整体观感比死灰高级很多。
     */
    public static Tokens of(int seed, boolean dark) {
        double h = Hct.hueOf(seed);
        double c = Math.min(Hct.chromaOf(seed), 60);

        // 三个强调色族：主色 / 次色（低色度）/ 三色（色相偏移 60°）
        double cSec = c * 0.34, cTer = c * 0.62;
        // 中性色：色度极低但非零
        double nc = Math.min(c * 0.055, 4.0);
        double nv = Math.min(c * 0.10, 8.0);
        // 错误色固定为红系，不随种子漂移
        final double EH = 25, EC = 60;

        B b = new B();
        b.dark = dark;
        b.seed = seed;

        // ── Primary ──
        b.primary = tone(seed, h, c, dark ? 80 : 40);
        b.onPrimary = dark ? tone(seed, h, c, 20) : Color.WHITE;
        b.primaryContainer = tone(seed, h, cTer, dark ? 30 : 90);
        b.onPrimaryContainer = tone(seed, h, cTer, dark ? 90 : 10);
        b.primaryFixedDim = tone(seed, h, c, 80);

        // ── Secondary ──
        b.secondary = tone(seed, h, cSec, dark ? 80 : 40);
        b.onSecondary = dark ? tone(seed, h, cSec, 20) : Color.WHITE;
        b.secondaryContainer = tone(seed, h, cSec, dark ? 30 : 90);
        b.onSecondaryContainer = tone(seed, h, cSec, dark ? 90 : 10);

        // ── Tertiary（色相偏移，让界面有层次） ──
        b.tertiary = tone(seed, h + 60, cTer, dark ? 80 : 40);
        b.onTertiary = dark ? tone(seed, h + 60, cTer, 20) : Color.WHITE;
        b.tertiaryContainer = tone(seed, h + 60, cTer, dark ? 30 : 90);
        b.onTertiaryContainer = tone(seed, h + 60, cTer, dark ? 90 : 10);

        // ── Error ──
        b.error = tone(seed, EH, EC, dark ? 80 : 40);
        b.onError = tone(seed, EH, EC, dark ? 20 : 100);
        b.errorContainer = tone(seed, EH, EC, dark ? 30 : 90);
        b.onErrorContainer = tone(seed, EH, EC, dark ? 90 : 10);

        // ── Surfaces ──
        b.background = tone(seed, h, nc, dark ? 6 : 99);
        b.onBackground = tone(seed, h, nc, dark ? 90 : 10);
        b.surface = tone(seed, h, nc, dark ? 6 : 98);
        b.onSurface = tone(seed, h, nc, dark ? 90 : 10);
        b.surfaceVariant = tone(seed, h, nv, dark ? 30 : 90);
        b.onSurfaceVariant = tone(seed, h, nv, dark ? 80 : 30);
        b.surfaceDim = tone(seed, h, nc, dark ? 6 : 87);
        b.surfaceBright = tone(seed, h, nc, dark ? 24 : 98);
        b.surfaceContainerLowest = tone(seed, h, nc, dark ? 4 : 100);
        b.surfaceContainerLow = tone(seed, h, nc, dark ? 10 : 96);
        b.surfaceContainer = tone(seed, h, nc, dark ? 12 : 94);
        b.surfaceContainerHigh = tone(seed, h, nc, dark ? 17 : 92);
        b.surfaceContainerHighest = tone(seed, h, nc, dark ? 22 : 90);

        // ── Outline / Inverse ──
        b.outline = tone(seed, h, nv, dark ? 60 : 50);
        b.outlineVariant = tone(seed, h, nv, dark ? 30 : 80);
        b.scrim = 0xFF000000;
        b.inverseSurface = tone(seed, h, nc, dark ? 90 : 20);
        b.inverseOnSurface = tone(seed, h, nc, dark ? 20 : 95);
        b.inversePrimary = tone(seed, h, c, dark ? 40 : 80);
        b.shadowTint = tone(seed, h, nc, dark ? 0 : 25);

        return b.build();
    }

    private static int tone(int seed, double hueDeg, double chroma, double tone) {
        return Hct.fromLab(tone, Math.cos(Math.toRadians(hueDeg)) * chroma,
                Math.sin(Math.toRadians(hueDeg)) * chroma);
    }

    /** 主色低透明度的表面染色（MD3 elevation 的 surface tint） */
    public int tintedSurface(int base, double elevation) {
        return Hct.composite(primary, Math.min(0.14, elevation * 0.05), base);
    }

    /** 卡片/容器的柔和阴影色 */
    public int shadow(double alpha) {
        return Hct.withAlpha(shadowTint, alpha);
    }

    /** 由新种子生成同明暗的配色（用于跟随封面变色） */
    public Tokens withSeed(int newSeed) {
        return Tokens.of(newSeed, dark);
    }

    // ── 预设种子色 ──────────────────────────────────────────────────────────
    public static final String[] SEED_NAMES = {
            "Qusic 蓝", "夜空靛", "电光紫", "樱花粉", "日落橙",
            "抹茶绿", "薄荷青", "珊瑚红", "拿铁棕", "石墨灰"
    };
    public static final int[] SEEDS = {
            0xFF4C6FFF, 0xFF3F51B5, 0xFF7C4DFF, 0xFFE91E63, 0xFFFF7043,
            0xFF66BB6A, 0xFF00BFA5, 0xFFFF5252, 0xFF8D6E63, 0xFF607D8B
    };
}
