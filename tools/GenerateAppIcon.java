import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Draws the application icon (a down arrow into a drive, white on a rounded blue square) and writes the
 * committed assets: the PNGs the JavaFX windows load and the multi-size .ico jpackage embeds in the .exe.
 * Run from the repository root after changing the drawing: {@code java tools/GenerateAppIcon.java}
 */
public class GenerateAppIcon {

	private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};
	private static final Path PNG_DIRECTORY = Path.of("src/main/resources/icons");
	private static final Path ICO_FILE = Path.of("src/main/packaging/windows/gdrive-backup.ico");

	public static void main(String[] args) throws IOException {
		Files.createDirectories(PNG_DIRECTORY);
		Files.createDirectories(ICO_FILE.getParent());
		List<BufferedImage> images = new ArrayList<>();
		for (int size : SIZES) {
			BufferedImage image = draw(size);
			images.add(image);
			ImageIO.write(image, "png", PNG_DIRECTORY.resolve("app-icon-" + size + ".png").toFile());
		}
		Files.write(ICO_FILE, ico(images));
	}

	/** The drawing is laid out on a 256 x 256 grid and scaled to the requested size. */
	private static BufferedImage draw(int size) {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		graphics.setTransform(AffineTransform.getScaleInstance(size / 256.0, size / 256.0));

		graphics.setPaint(new GradientPaint(0, 8, new Color(0x2f86f2), 0, 248, new Color(0x1765cc)));
		graphics.fill(new RoundRectangle2D.Double(8, 8, 240, 240, 104, 104));

		graphics.setPaint(Color.WHITE);
		Path2D arrow = new Path2D.Double();
		arrow.moveTo(110, 44);
		arrow.lineTo(146, 44);
		arrow.lineTo(146, 92);
		arrow.lineTo(178, 92);
		arrow.lineTo(128, 142);
		arrow.lineTo(78, 92);
		arrow.lineTo(110, 92);
		arrow.closePath();
		graphics.fill(arrow);

		graphics.fill(new RoundRectangle2D.Double(46, 158, 164, 54, 28, 28));
		// The drive's activity light; below 32 px it would be a sub-pixel smudge.
		if (size >= 32) {
			graphics.setPaint(new Color(0x1a73e8));
			graphics.fill(new Ellipse2D.Double(170, 174, 22, 22));
		}
		graphics.dispose();
		return image;
	}

	/** 32-bit BMP entries up to 128 px and a PNG entry for 256 px, the layout Windows expects. */
	private static byte[] ico(List<BufferedImage> images) throws IOException {
		List<byte[]> payloads = new ArrayList<>();
		for (BufferedImage image : images) {
			payloads.add(image.getWidth() >= 256 ? png(image) : bitmap(image));
		}
		int offset = 6 + 16 * images.size();
		int total = offset + payloads.stream().mapToInt(payload -> payload.length).sum();
		ByteBuffer buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putShort((short) 0).putShort((short) 1).putShort((short) images.size());
		for (int index = 0; index < images.size(); index++) {
			int size = images.get(index).getWidth();
			byte dimension = (byte) (size >= 256 ? 0 : size);
			buffer.put(dimension).put(dimension).put((byte) 0).put((byte) 0);
			buffer.putShort((short) 1).putShort((short) 32);
			buffer.putInt(payloads.get(index).length).putInt(offset);
			offset += payloads.get(index).length;
		}
		payloads.forEach(buffer::put);
		return buffer.array();
	}

	private static byte[] png(BufferedImage image) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	/** A BITMAPINFOHEADER, the bottom-up BGRA pixels, then an all-zero AND mask (alpha does the masking). */
	private static byte[] bitmap(BufferedImage image) {
		int size = image.getWidth();
		int maskRowBytes = (size + 31) / 32 * 4;
		int pixelBytes = size * size * 4;
		ByteBuffer buffer = ByteBuffer.allocate(40 + pixelBytes + maskRowBytes * size)
				.order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(40).putInt(size).putInt(size * 2).putShort((short) 1).putShort((short) 32);
		buffer.putInt(0).putInt(pixelBytes).putInt(0).putInt(0).putInt(0).putInt(0);
		for (int y = size - 1; y >= 0; y--) {
			for (int x = 0; x < size; x++) {
				buffer.putInt(image.getRGB(x, y));
			}
		}
		return buffer.array();
	}
}
