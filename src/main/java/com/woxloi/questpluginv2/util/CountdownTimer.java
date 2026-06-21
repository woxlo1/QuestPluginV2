package com.woxloi.questpluginv2.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 1秒間隔のカウントダウンタイマー。
 *
 * 修正点:
 *  - 生Threadでのポーリングをやめ、BukkitScheduler#runTaskTimer に置き換えた。
 *    タイムリミット付きクエストが同時に多数走ってもスレッドが増えない。
 *  - メインスレッド上で動作するため、tick / finish リスナーは
 *    そのままBukkit APIを呼んでよい（以前は呼び出し側で
 *    Bukkit.getScheduler().runTask() に包む必要があった）。
 *  - stop() はスケジュールされたタスクを確実にキャンセルする。
 *    onDisable() 等で停止漏れがあると残り続けていた問題に対応。
 */
public class CountdownTimer {

    private final Plugin plugin;
    private int duration;
    private int remaining;

    private volatile boolean running;
    private BukkitTask task;

    private final List<Runnable> finishListeners = new ArrayList<>();
    private final List<IntConsumer> tickListeners = new ArrayList<>();

    public CountdownTimer(Plugin plugin, int seconds) {
        this.plugin = plugin;
        this.duration = seconds;
        this.remaining = seconds;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;

        // 即時に1回通知してから、1秒(20tick)ごとに減算する
        notifyTick();

        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!running) {
                return;
            }

            remaining--;
            notifyTick();

            if (remaining <= 0) {
                running = false;
                if (task != null) {
                    task.cancel();
                    task = null;
                }
                notifyFinish();
            }
        }, 20L, 20L);
    }

    /**
     * タイマーを停止する。スケジュールされたタスクを確実にキャンセルする。
     * 既に停止済みでも安全に呼べる。
     */
    public void stop() {
        running = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void reset() {
        stop();
        remaining = duration;
    }

    public void setTime(int seconds) {
        this.duration = seconds;
        this.remaining = seconds;
    }

    public int getRemaining() {
        return remaining;
    }

    public int getDuration() {
        return duration;
    }

    public boolean isRunning() {
        return running;
    }

    public void addFinishListener(Runnable listener) {
        finishListeners.add(listener);
    }

    public void addTickListener(IntConsumer listener) {
        tickListeners.add(listener);
    }

    private void notifyTick() {
        for (IntConsumer listener : tickListeners) {
            listener.accept(remaining);
        }
    }

    private void notifyFinish() {
        for (Runnable listener : finishListeners) {
            listener.run();
        }
    }
}