package com.desklink.core;

import java.util.ArrayList;
import java.util.List;

/** Parsed host → client payloads. */
public final class Messages {
    private Messages() {}

    public static final class Monitor {
        public int index;
        public int left, top, width, height;
        public boolean primary;
        public String name = "";

        @Override
        public String toString() {
            return "#" + index + " " + name + " " + width + "x" + height + "@" + left + "," + top + (primary ? " (primary)" : "");
        }
    }

    public static final class HostInfo {
        public byte[] hostId = new byte[16];
        public String hostName = "";
        public long capabilities;
        public final List<Monitor> monitors = new ArrayList<>();
        public final List<String> addresses = new ArrayList<>();
        /** Protocol.REMOTE_* (what reaches the PC over mobile data); -1 = an older PC that doesn't say. */
        public int remoteFlags = -1;

        public boolean can(int cap) { return (capabilities & cap) != 0; }

        static HostInfo parse(ByteReader r) {
            HostInfo h = new HostInfo();
            h.hostId = r.bytes(16);
            h.hostName = r.str();
            h.capabilities = r.u32();
            int n = r.u8();
            for (int i = 0; i < n; i++) {
                Monitor m = new Monitor();
                m.index = r.u8();
                m.left = r.i32();
                m.top = r.i32();
                m.width = r.i32();
                m.height = r.i32();
                m.primary = r.u8() != 0;
                m.name = r.str();
                h.monitors.add(m);
            }
            int a = r.u8();
            for (int i = 0; i < a; i++) h.addresses.add(r.str());
            if (r.remaining() > 0) h.remoteFlags = r.u8();
            return h;
        }

        @Override
        public String toString() {
            return "HostInfo{" + hostName + " id=" + Hex.hex(hostId) + " caps=" + capabilities + " monitors=" + monitors + " addresses=" + addresses + "}";
        }
    }

    /** AUDIO_FORMAT: what the AUDIO_DATA frames that follow are (codec NONE: the PC has no sound output right now). */
    public static final class AudioFormat {
        public int codec;
        public int sampleRate;
        public int channels;
        /** AAC: the AudioSpecificConfig (the decoder's csd-0). */
        public byte[] config = new byte[0];

        public static AudioFormat parse(ByteReader r) {
            AudioFormat f = new AudioFormat();
            f.codec = r.u8();
            f.sampleRate = (int) r.u32();
            f.channels = r.u8();
            f.config = r.tail();
            return f;
        }
    }

    public static final class VideoFormat {
        public int codec;
        public int width, height;
        public int monitor;
        public int desktopLeft, desktopTop, desktopWidth, desktopHeight;

        static VideoFormat parse(ByteReader r) {
            VideoFormat f = new VideoFormat();
            f.codec = r.u8();
            f.width = r.u16();
            f.height = r.u16();
            f.monitor = r.u8();
            f.desktopLeft = r.i32();
            f.desktopTop = r.i32();
            f.desktopWidth = r.i32();
            f.desktopHeight = r.i32();
            return f;
        }

        @Override
        public String toString() {
            return "VideoFormat{codec=" + codec + " " + width + "x" + height + " monitor=" + monitor
                    + " desktop=" + desktopWidth + "x" + desktopHeight + "@" + desktopLeft + "," + desktopTop + "}";
        }
    }

    public static final class CursorShape {
        public int id;
        public int width, height, hotX, hotY;
        public byte[] rgba;

        static CursorShape parse(ByteReader r) {
            CursorShape c = new CursorShape();
            c.id = r.i32();
            c.width = r.u16();
            c.height = r.u16();
            c.hotX = r.u16();
            c.hotY = r.u16();
            int need = c.width * c.height * 4;
            if (r.remaining() < need) throw new MalformedMessageException("cursor shape truncated");
            c.rgba = r.bytes(need);
            return c;
        }
    }

    public static final class StatusResult {
        public int status;
        public int flags;
        public String hostName = "";
        public String activeDeviceName = "";
        public long uptimeSeconds;
        /** Address the probe reached the PC at (filled in by the prober). */
        public String address = "";

        public boolean inUse() { return (flags & Protocol.SQ_IN_USE) != 0; }

        public boolean secureDesktop() { return (flags & Protocol.SQ_SECURE_DESKTOP) != 0; }

        public boolean serviceMode() { return (flags & Protocol.SQ_SERVICE_MODE) != 0; }

        static StatusResult parse(ByteReader r) {
            StatusResult s = new StatusResult();
            s.status = r.u8();
            s.flags = r.u8();
            s.hostName = r.str();
            s.activeDeviceName = r.str();
            s.uptimeSeconds = r.u32();
            return s;
        }

        @Override
        public String toString() {
            return "Status{" + Protocol.statusName(status) + " flags=" + flags + " host=" + hostName
                    + (inUse() ? " inUseBy=" + activeDeviceName : "") + (secureDesktop() ? " SECURE_DESKTOP" : "")
                    + (serviceMode() ? " SERVICE" : "") + " uptime=" + uptimeSeconds + "s via " + address + "}";
        }
    }
}
