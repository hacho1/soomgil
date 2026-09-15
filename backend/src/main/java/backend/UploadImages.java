package backend;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class UploadImages {
    static final int MAX_ENCODED = 2_000_000;
    static final long MAX_PIXELS = 4_000_000;
    private static final Semaphore DECODERS = new Semaphore(2);
    static String validate(String value,String previous) {
        if (value.isEmpty()) return value;
        if (value.length() > MAX_ENCODED) throw invalid("사진이나 그림 용량이 너무 큽니다. 작은 이미지를 선택해주세요.");
        // Keep an existing external photo editable, but never fetch user-supplied URLs on the server.
        if (value.equals(previous) && value.startsWith("https://") && value.length()<=2000) {
            try {
                var uri=URI.create(value);
                if (uri.getHost()!=null && uri.getUserInfo()==null) return value;
            } catch (IllegalArgumentException ignored) { }
        }
        int comma=value.indexOf(',');
        if (comma<0) throw invalid("JPG, PNG, WebP 사진 파일을 선택해주세요.");
        String header=value.substring(0,comma);
        if (!Set.of("data:image/jpeg;base64","data:image/png;base64","data:image/webp;base64").contains(header)) throw invalid("JPG, PNG, WebP 사진을 선택해주세요.");
        byte[] bytes;
        try { bytes=Base64.getDecoder().decode(value.substring(comma+1)); }
        catch (IllegalArgumentException failure) { throw invalid("이미지 데이터를 읽을 수 없습니다."); }
        if (!DECODERS.tryAcquire()) throw new AuthRateLimit.Exceeded(2);
        try (var stream=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw invalid("실제 이미지 파일인지 확인해주세요.");
            var reader=readers.next();
            try {
                String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                if (format.equals("jpg")) format="jpeg";
                if (!header.equals("data:image/"+format+";base64")) throw invalid("이미지 형식이 파일 내용과 일치하지 않습니다.");
                reader.setInput(stream,true,true);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if (width<1 || height<1 || width>4096 || height>4096 || (long)width*height>MAX_PIXELS) throw invalid("이미지 해상도가 너무 큽니다. 크기를 줄여주세요.");
                var decoded=reader.read(0);
                if (decoded==null) throw invalid("손상된 이미지입니다.");
                decoded.flush();
            } finally { reader.dispose(); }
        } catch (java.io.IOException | IllegalArgumentException failure) { throw invalid("이미지를 열 수 없습니다. 다른 사진을 선택해주세요."); }
        finally { DECODERS.release(); }
        return value;
    }
    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
