package net.sf.sockettest;

import java.awt.*;

public class YixianTest {

    public static void main(String[] args) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        String[] fontFamilies = ge.getAvailableFontFamilyNames();

        for (String font : fontFamilies) {
            System.out.println(font);
        }
    }

}
