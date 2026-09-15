package backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class RequestBodyLimitTests {
    @Test void rejectsDeclaredOversizeAndCompressedBodiesBeforeProcessing() throws Exception {
        var filter=new RequestBodyLimit(new ObjectMapper());
        var request=new MockHttpServletRequest("POST","/api/anything") {
            @Override public long getContentLengthLong() { return RequestBodyLimit.MAX_BYTES+1; }
        };
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res) -> { throw new AssertionError("Must reject before parsing"); });
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("message");
        var compressed=new MockHttpServletRequest("POST","/api/anything");
        compressed.addHeader("Content-Encoding","gzip");
        response=new MockHttpServletResponse();
        filter.doFilter(compressed,response,(req,res) -> { throw new AssertionError("Must not decompress"); });
        assertThat(response.getStatus()).isEqualTo(415);
    }

    @Test void unknownLengthAndReaderAreBoundedWhileExactLimitAndEmptyBodiesWork() throws Exception {
        var request=new MockHttpServletRequest() {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setContent("abcde".getBytes(StandardCharsets.UTF_8));
        var limited=new RequestBodyLimit.LimitedRequest(request,4);
        assertThatThrownBy(() -> limited.getInputStream().readAllBytes()).isInstanceOf(RequestBodyLimit.Exceeded.class);
        request.setContent("abcde".getBytes(StandardCharsets.UTF_8));
        var reader=new RequestBodyLimit.LimitedRequest(request,4);
        assertThatThrownBy(() -> reader.getReader().readLine()).isInstanceOf(RequestBodyLimit.Exceeded.class);
        request.setContent("abcd".getBytes(StandardCharsets.UTF_8));
        assertThat(new RequestBodyLimit.LimitedRequest(request,4).getInputStream().readAllBytes()).hasSize(4);
        request.setContent(new byte[0]);
        assertThat(new RequestBodyLimit.LimitedRequest(request,4).getInputStream().read()).isEqualTo(-1);
    }
}
