package com.crunkazcanbe.claudecraft;

import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.Deque;

/** Keeps the last chat lines (command results, mod messages, action-bar text) so the controller can read them back. */
public final class ChatLog {
    private static final Deque<String> LINES = new ArrayDeque<>();

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent e) {
        String line = (e.getType() == net.minecraft.util.text.ChatType.GAME_INFO ? "[bar] " : "") + (e.getMessage() == null ? "" : e.getMessage().getUnformattedText());
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > 50) LINES.removeFirst();
        }
    }

    static String last(int n) {
        synchronized (LINES) {
            StringBuilder sb = new StringBuilder();
            int skip = Math.max(0, LINES.size() - n);
            for (String l : LINES) {
                if (skip-- > 0) continue;
                sb.append(l.replace("\n", " ")).append(" | ");
            }
            return sb.length() == 0 ? "(no chat yet)" : sb.toString();
        }
    }
}
