package com.qusic.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 「墨水向上散去」的转场效果。
 *
 * <p>替代原来的烟花：点击后整个界面像被水洇开的墨一样向上飘散。
 * 由两层构成：
 * <ol>
 *   <li>若干柔边墨团：从界面各处升起，边升边扩散、边变淡，略带横向摆动</li>
 *   <li>整体上浮：调用方同时把内容整体上移并淡出，形成「散开」的感觉</li>
 * </ol>
 *
 * <p>用 {@link View#postOnAnimation} 驱动，dt 上限 50ms 防止掉帧穿模。
 */
public class InkDissolve extends View {

    public interface OnDone { void onFinished(); }

    private static final int COUNT = 26;

    private final List<Blob> blobs = new ArrayList<>();
    private final Random rnd = new Random();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long lastFrame;
    private boolean running;
    private OnDone done;
    private float progress;
    private int accent;

    private static class Blob {
        float x, y, vx, vy, r0, r1, life, maxLife, sway, phase;
    }

    public InkDissolve(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        setClickable(false);
        setWillNotDraw(false);
    }

    /** 开始散开动画；progress 会从 0 走到 1 */
    public void start(int accentColor, OnDone cb) {
        if (running) return;
        this.accent = accentColor;
        this.done = cb;
        blobs.clear();
        running = true;
        progress = 0f;
        lastFrame = 0;
        float w = getWidth() > 0 ? getWidth() : Ui.screenW(getContext());
        float h = getHeight() > 0 ? getHeight() : Ui.screenH(getContext());
        for (int i = 0; i < COUNT; i++) {
            Blob b = new Blob();
            b.x = w * (0.05f + rnd.nextFloat() * 0.9f);
            b.y = h * (0.15f + rnd.nextFloat() * 0.9f);
            b.vy = -(h * (0.35f + rnd.nextFloat() * 0.55f));   // 向上
            b.vx = (rnd.nextFloat() - 0.5f) * w * 0.10f;
            b.r0 = w * (0.03f + rnd.nextFloat() * 0.06f);
            b.r1 = b.r0 * (2.6f + rnd.nextFloat() * 2.4f);
            b.maxLife = 0.75f + rnd.nextFloat() * 0.5f;
            b.life = b.maxLife;
            b.sway = (rnd.nextFloat() - 0.5f) * w * 0.06f;
            b.phase = rnd.nextFloat() * 6.28f;
            blobs.add(b);
        }
        postOnAnimation(loop);
    }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            step(dt);
            invalidate();
            if (progress < 1f || !blobs.isEmpty()) postOnAnimation(this);
            else {
                running = false;
                if (done != null) done.onFinished();
            }
        }
    };

    private void step(float dt) {
        progress = Math.min(1f, progress + dt / 1.05f);
        for (int i = blobs.size() - 1; i >= 0; i--) {
            Blob b = blobs.get(i);
            b.life -= dt;
            if (b.life <= 0) { blobs.remove(i); continue; }
            b.y += b.vy * dt;
            b.vy *= (1f - 0.35f * dt);                    // 上升逐渐减速
            b.x += b.vx * dt + (float) Math.sin(b.phase += dt * 1.6f) * b.sway * dt * 3f;
        }
    }

    @Override protected void onDraw(Canvas c) {
        int tint = accent != 0 ? accent : Theme.t().primary;
        float overall = 1f - progress * 0.55f;   // 整体也随时间淡出
        for (Blob b : blobs) {
            float f = Math.max(0f, b.life / b.maxLife);
            float grow = 1f - f;                  // 越飘越大
            float r = b.r0 + (b.r1 - b.r0) * grow;
            float a = f * f * 0.55f * overall;
            if (r <= 0.5f || a <= 0.004f) continue;
            p.reset();
            p.setShader(new RadialGradient(b.x, b.y, r,
                    new int[]{Hct.withAlpha(tint, a),
                            Hct.withAlpha(Hct.blendLab(tint, Theme.t().tertiary, 0.5f), a * 0.55f),
                            Hct.withAlpha(tint, 0f)},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(b.x, b.y, r, p);
            p.setShader(null);
        }
    }
}
