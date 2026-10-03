import io.specqr.*;
import java.nio.file.*;
import java.util.List;

/** java -cp dist/specqr-java-0.1.0-rc.1.jar examples/Basic.java */
class Basic {
  public static void main(String[] args) throws Exception {
    QrCode qr =
        SpecQr.generate("こんにちは、SpecQR!", Options.builder().errorCorrectionLevel("Q").build());
    Files.write(Path.of("hello.png"), qr.toPng());
    Files.writeString(Path.of("hello.svg"), qr.toSvg());
    QrCode mixed =
        SpecQr.generateSegments(List.of(Segment.numeric("1234567890"), Segment.bytes("abc")));
    System.out.println(
        "Version "
            + qr.version()
            + ", mask "
            + qr.maskPattern()
            + ", mixed version "
            + mixed.version());
    Plan plan = SpecQr.estimate("A".repeat(500));
    System.out.println(
        "Fits: " + plan.ok() + ", version " + plan.version() + ", bits " + plan.requiredBits());
  }
}
