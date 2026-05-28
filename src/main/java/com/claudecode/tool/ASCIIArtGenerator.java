// ASCIIArtGenerator.java - 改进版
package com.claudecode.tool;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * ASCII艺术字生成器
 * 支持文本转ASCII艺术字和图片转ASCII艺术字
 */
public class ASCIIArtGenerator {

    /** 字符集 - 从暗到亮 */
    private static final String DEFAULT_CHARS = "@%#*+=-:. ";
    private static final String DENSE_CHARS = "$@B%8&WM#*oahkbdpqwmZO0QLCJUYXzcvunxrjft/|()1{}[]?-_+~<>i!lI;:,\"^`'. ";

    /** 完整的ASCII字体映射 */
    private static final Map<Character, String[]> ASCII_FONT = new HashMap<>();

    static {
        initFont();
    }

    /**
     * 初始化ASCII字体 - 定义每个字符的7行表示
     */
    private static void initFont() {
        // A
        ASCII_FONT.put('A', new String[]{
                "  ████  ",
                " ██  ██ ",
                " ██████ ",
                " ██  ██ ",
                " ██  ██ ",
                "        "
        });
        // B
        ASCII_FONT.put('B', new String[]{
                " █████  ",
                " ██  ██ ",
                " █████  ",
                " ██  ██ ",
                " █████  ",
                "        "
        });
        // C
        ASCII_FONT.put('C', new String[]{
                "  ████  ",
                " ██     ",
                " ██     ",
                " ██     ",
                "  ████  ",
                "        "
        });
        // D
        ASCII_FONT.put('D', new String[]{
                " █████  ",
                " ██  ██ ",
                " ██  ██ ",
                " ██  ██ ",
                " █████  ",
                "        "
        });
        // E
        ASCII_FONT.put('E', new String[]{
                " ██████ ",
                " ██     ",
                " █████  ",
                " ██     ",
                " ██████ ",
                "        "
        });
        // F
        ASCII_FONT.put('F', new String[]{
                " ██████ ",
                " ██     ",
                " █████  ",
                " ██     ",
                " ██     ",
                "        "
        });
        // G
        ASCII_FONT.put('G', new String[]{
                "  ████  ",
                " ██     ",
                " ██ ███ ",
                " ██  ██ ",
                "  ████  ",
                "        "
        });
        // H
        ASCII_FONT.put('H', new String[]{
                " ██  ██ ",
                " ██  ██ ",
                " ██████ ",
                " ██  ██ ",
                " ██  ██ ",
                "        "
        });
        // I
        ASCII_FONT.put('I', new String[]{
                " ██████ ",
                "   ██   ",
                "   ██   ",
                "   ██   ",
                " ██████ ",
                "        "
        });
        // J
        ASCII_FONT.put('J', new String[]{
                "   ████ ",
                "     ██ ",
                "     ██ ",
                " ██  ██ ",
                "  ███   ",
                "        "
        });
        // K
        ASCII_FONT.put('K', new String[]{
                " ██  ██ ",
                " ██ ██  ",
                " ████   ",
                " ██ ██  ",
                " ██  ██ ",
                "        "
        });
        // L
        ASCII_FONT.put('L', new String[]{
                " ██     ",
                " ██     ",
                " ██     ",
                " ██     ",
                " ██████ ",
                "        "
        });
        // M
        ASCII_FONT.put('M', new String[]{
                " ██    ██ ",
                " ███  ███ ",
                " ██ ██ ██ ",
                " ██    ██ ",
                " ██    ██ ",
                "        "
        });
        // N
        ASCII_FONT.put('N', new String[]{
                " ██  ██ ",
                " ███ ██ ",
                " ██ ███ ",
                " ██  ██ ",
                " ██  ██ ",
                "        "
        });
        // O
        ASCII_FONT.put('O', new String[]{
                "  ████  ",
                " ██  ██ ",
                " ██  ██ ",
                " ██  ██ ",
                "  ████  ",
                "        "
        });
        // P
        ASCII_FONT.put('P', new String[]{
                " █████  ",
                " ██  ██ ",
                " █████  ",
                " ██     ",
                " ██     ",
                "        "
        });
        // Q
        ASCII_FONT.put('Q', new String[]{
                "  ████  ",
                " ██  ██ ",
                " ██  ██ ",
                " ██ ██  ",
                "  ███ █ ",
                "        "
        });
        // R
        ASCII_FONT.put('R', new String[]{
                " █████  ",
                " ██  ██ ",
                " █████  ",
                " ██ ██  ",
                " ██  ██ ",
                "        "
        });
        // S
        ASCII_FONT.put('S', new String[]{
                "  ████  ",
                " ██     ",
                "  ████  ",
                "     ██ ",
                " ████   ",
                "        "
        });
        // T
        ASCII_FONT.put('T', new String[]{
                " ██████ ",
                "   ██   ",
                "   ██   ",
                "   ██   ",
                "   ██   ",
                "        "
        });
        // U
        ASCII_FONT.put('U', new String[]{
                " ██  ██ ",
                " ██  ██ ",
                " ██  ██ ",
                " ██  ██ ",
                "  ████  ",
                "        "
        });
        // V
        ASCII_FONT.put('V', new String[]{
                " ██  ██ ",
                " ██  ██ ",
                " ██  ██ ",
                "  ██ ██ ",
                "   ███  ",
                "        "
        });
        // W
        ASCII_FONT.put('W', new String[]{
                " ██  ██ ",
                " ██  ██ ",
                " ██  ██ ",
                " ██████ ",
                " ██  ██ ",
                "        "
        });
        // X
        ASCII_FONT.put('X', new String[]{
                " ██  ██ ",
                " ██  ██ ",
                "  ████  ",
                " ██  ██ ",
                " ██  ██ ",
                "        "
        });
        // Y
        ASCII_FONT.put('Y', new String[]{
                " ██  ██ ",
                " ██  ██ ",
                "  ████  ",
                "   ██   ",
                "   ██   ",
                "        "
        });
        // Z
        ASCII_FONT.put('Z', new String[]{
                " ██████ ",
                "     ██ ",
                "  ████  ",
                " ██     ",
                " ██████ ",
                "        "
        });
        // 0
        ASCII_FONT.put('0', new String[]{
                "  ████  ",
                " ██  ██ ",
                " ██  ██ ",
                " ██  ██ ",
                "  ████  ",
                "        "
        });
        // 1
        ASCII_FONT.put('1', new String[]{
                "   ██   ",
                " ████   ",
                "   ██   ",
                "   ██   ",
                " ██████ ",
                "        "
        });
        // 2
        ASCII_FONT.put('2', new String[]{
                "  ████  ",
                " ██  ██ ",
                "     ██ ",
                "  ██    ",
                " ██████ ",
                "        "
        });
        // 3
        ASCII_FONT.put('3', new String[]{
                "  ████  ",
                " ██  ██ ",
                "   ████ ",
                "     ██ ",
                "  ████  ",
                "        "
        });
        // 4
        ASCII_FONT.put('4', new String[]{
                " ██  ██ ",
                " ██  ██ ",
                " ██████ ",
                "     ██ ",
                "     ██ ",
                "        "
        });
        // 5
        ASCII_FONT.put('5', new String[]{
                " ██████ ",
                " ██     ",
                " ██████ ",
                "     ██ ",
                " ██████ ",
                "        "
        });
        // 6
        ASCII_FONT.put('6', new String[]{
                "  ████  ",
                " ██     ",
                " ██████ ",
                " ██  ██ ",
                "  ████  ",
                "        "
        });
        // 7
        ASCII_FONT.put('7', new String[]{
                " ██████ ",
                "     ██ ",
                "    ██  ",
                "   ██   ",
                "  ██    ",
                "        "
        });
        // 8
        ASCII_FONT.put('8', new String[]{
                "  ████  ",
                " ██  ██ ",
                "  ████  ",
                " ██  ██ ",
                "  ████  ",
                "        "
        });
        // 9
        ASCII_FONT.put('9', new String[]{
                "  ████  ",
                " ██  ██ ",
                "  █████ ",
                "     ██ ",
                "  ████  ",
                "        "
        });
        // 空格
        ASCII_FONT.put(' ', new String[]{
                "        ",
                "        ",
                "        ",
                "        ",
                "        ",
                "        "
        });
        // 感叹号
        ASCII_FONT.put('!', new String[]{
                "  ██    ",
                "  ██    ",
                "  ██    ",
                "        ",
                "  ██    ",
                "        "
        });
        // 连字符
        ASCII_FONT.put('-', new String[]{
                "        ",
                "        ",
                " ██████ ",
                "        ",
                "        ",
                "        "
        });
        // 下划线
        ASCII_FONT.put('_', new String[]{
                "        ",
                "        ",
                "        ",
                "        ",
                " ██████ ",
                "        "
        });
    }

    /**
     * 将文本转换为ASCII艺术字
     * @param text 输入文本
     * @return ASCII艺术字字符串
     */
    public static String textToAscii(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        // 逐行生成（字体高度为5行）
        for (int row = 0; row < 6; row++) {
            for (char c : text.toUpperCase().toCharArray()) {
                String[] charArt = ASCII_FONT.get(c);
                if (charArt != null) {
                    result.append(charArt[row]);
                } else {
                    // 未知字符用问号代替
                    result.append("  ????  ");
                }
                // 字符之间的间隔
                result.append(" ");
            }
            result.append("\n");
        }

        return result.toString();
    }

    /**
     * 将图片转换为ASCII艺术字
     * @param imageFile 图片文件
     * @param width 输出宽度
     * @param useDenseChars 是否使用密集字符集
     * @return ASCII艺术字字符串
     * @throws IOException 读取图片失败时抛出
     */
    public static String imageToAscii(File imageFile, int width, boolean useDenseChars) throws IOException {
        BufferedImage image = ImageIO.read(imageFile);

        // 计算高度（考虑宽高比和字符宽高比）
        double aspectRatio = 0.5; // 字符的宽高比
        int height = (int) (width * image.getHeight() * aspectRatio / image.getWidth());

        // 缩小图片
        BufferedImage scaledImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaledImage.createGraphics();
        g.drawImage(image, 0, 0, width, height, null);
        g.dispose();

        // 选择字符集
        String chars = useDenseChars ? DENSE_CHARS : DEFAULT_CHARS;

        StringBuilder result = new StringBuilder();

        // 逐行转换
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = scaledImage.getRGB(x, y);
                char asciiChar = pixelToChar(pixel, chars);
                result.append(asciiChar);
            }
            result.append("\n");
        }

        return result.toString();
    }

    /**
     * 将像素转换为ASCII字符
     */
    private static char pixelToChar(int pixel, String chars) {
        // 获取RGB值
        int r = (pixel >> 16) & 0xff;
        int g = (pixel >> 8) & 0xff;
        int b = pixel & 0xff;

        // 计算灰度值
        double gray = 0.299 * r + 0.587 * g + 0.114 * b;

        // 根据灰度选择字符
        int index = (int) (gray / 255.0 * (chars.length() - 1));
        return chars.charAt(chars.length() - 1 - index); // 反转，让亮的对应稀疏字符
    }

    /**
     * 测试方法
     */
    public static void main(String[] args) {
        // 测试文本转ASCII
        System.out.println("文本转ASCII (miniCLI):");
        System.out.println(textToAscii("miniCLI"));

        System.out.println("文本转ASCII (HELLO WORLD):");
        System.out.println(textToAscii("HELLO WORLD"));

        // 测试图片转ASCII
        try {
            File imageFile = new File("test.jpg");
            if (imageFile.exists()) {
                System.out.println("图片转ASCII:");
                System.out.println(imageToAscii(imageFile, 80, true));
            }
        } catch (IOException e) {
            System.out.println("图片转换失败: " + e.getMessage());
        }
    }
}