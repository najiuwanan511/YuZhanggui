package com.yuzhanggui.service;

/** Resolves the dimensions encoded in an image URL before it is sent as a chat image. */
public interface ImageDimensionService {

    ImageDimensions resolve(String imageUrl);

    record ImageDimensions(int width, int height) {
        public static ImageDimensions unknown() {
            return new ImageDimensions(0, 0);
        }

        public boolean isKnown() {
            return width > 0 && height > 0;
        }
    }
}
