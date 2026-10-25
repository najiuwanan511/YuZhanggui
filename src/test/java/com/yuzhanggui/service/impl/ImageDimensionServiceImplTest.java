package com.yuzhanggui.service.impl;

import com.yuzhanggui.service.ImageDimensionService.ImageDimensions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageDimensionServiceImplTest {

    @Test
    void readsTheActualPortraitDimensionsInsteadOfAssumingASquare() throws Exception {
        BufferedImage source = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, Color.WHITE.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(source, "jpg", output);

        ImageDimensions dimensions = ImageDimensionServiceImpl.readDimensions(output.toByteArray());

        assertEquals(720, dimensions.width());
        assertEquals(1280, dimensions.height());
        assertTrue(dimensions.isKnown());
    }

    @Test
    void rejectsUnknownImageBytes() {
        assertThrows(Exception.class,
                () -> ImageDimensionServiceImpl.readDimensions(new byte[] {1, 2, 3}));
    }
}
