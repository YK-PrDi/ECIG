package com.elebusiness.service.video;

public record VideoAspectSpec(String aspectRatio, int width, int height) {

    public static VideoAspectSpec resolve(String aspectRatio) {
        String normalized = aspectRatio == null ? "" : aspectRatio.trim();
        return switch (normalized) {
            case "9:16" -> new VideoAspectSpec("9:16", 720, 1280);
            case "1:1" -> new VideoAspectSpec("1:1", 720, 720);
            case "4:3" -> new VideoAspectSpec("4:3", 960, 720);
            case "3:4" -> new VideoAspectSpec("3:4", 720, 960);
            case "3:2" -> new VideoAspectSpec("3:2", 1080, 720);
            case "2:3" -> new VideoAspectSpec("2:3", 720, 1080);
            default -> new VideoAspectSpec("16:9", 1280, 720);
        };
    }

    public String apiSize() {
        return width + "x" + height;
    }
}
