package backend;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class UploadImagesTests {
    static String image(String format,int width,int height) throws Exception {
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        var bytes=new ByteArrayOutputStream(); assertThat(ImageIO.write(image,format,bytes)).isTrue(); image.flush();
        return "data:image/"+format+";base64,"+Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    @Test void actualJpegPngAndWebpDecodeButDeclaredMimeMustMatch() throws Exception {
        for (String format : new String[]{"jpeg","png"}) {
            String data=image(format,4,4); assertThat(UploadImages.validate(data,"")).isEqualTo(data);
        }
        String webp="data:image/webp;base64,UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA";
        assertThat(UploadImages.validate(webp,"")).isEqualTo(webp);
        String mislabeled=image("png",4,4).replace("image/png","image/jpeg");
        assertThatThrownBy(() -> UploadImages.validate(mislabeled,"")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void invalidImagesAndOversizedDimensionsAreRejectedBeforeFullDecode() throws Exception {
        String large=image("png",4097,1);
        String tooMany=image("png",2001,2000);
        for (String data : new String[]{large,tooMany,"data:image/png;base64,AAAA","data:image/png;base64,???","data:image/svg+xml;base64,AAAA","x".repeat(UploadImages.MAX_ENCODED+1)})
            assertThatThrownBy(() -> UploadImages.validate(data,"")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void onlyUnchangedLegacyHttpsPhotosSurviveWithoutFetchingRemoteContent() {
        String url="https://example.test/old-photo.jpg";
        assertThat(UploadImages.validate(url,url)).isEqualTo(url);
        assertThatThrownBy(() -> UploadImages.validate(url,"")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> UploadImages.validate("https://user:secret@example.test/a","https://user:secret@example.test/a")).isInstanceOf(ResponseStatusException.class);
    }
}
