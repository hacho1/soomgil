package backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestBodyLimit extends OncePerRequestFilter {
    static final long MAX_BYTES = 4_500_000;
    static final String MESSAGE = "전송 용량이 너무 큽니다. 사진이나 그림 크기를 줄여주세요.";
    private final ObjectMapper mapper;
    public RequestBodyLimit(ObjectMapper mapper) { this.mapper = mapper; }
    static class Exceeded extends IOException { Exceeded() { super(MESSAGE); } }
    static boolean exceeded(Throwable error) {
        for (int i=0; error != null && i<20; i++, error=error.getCause()) if (error instanceof Exceeded) return true;
        return false;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BYTES) { reject(response,413,MESSAGE); return; }
        String encoding = request.getHeader("Content-Encoding");
        if (encoding != null && !encoding.equalsIgnoreCase("identity")) { reject(response,415,"압축된 요청 본문은 지원하지 않습니다."); return; }
        try { chain.doFilter(new LimitedRequest(request,MAX_BYTES),response); }
        catch (ServletException | IOException failure) {
            if (!exceeded(failure) || response.isCommitted()) throw failure;
            reject(response,413,MESSAGE);
        }
    }
    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getWriter(),Map.of("message",message));
    }
    static class LimitedRequest extends HttpServletRequestWrapper {
        private final long limit;
        private ServletInputStream stream;
        LimitedRequest(HttpServletRequest request,long limit) { super(request); this.limit=limit; }
        @Override public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                var original = super.getInputStream();
                stream = new ServletInputStream() {
                    long count;
                    private int counted(int amount) throws IOException { if (amount>0) count+=amount; if (count>limit) throw new Exceeded(); return amount; }
                    @Override public int read() throws IOException { int value=original.read(); counted(value<0 ? 0 : 1); return value; }
                    @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                        return counted(original.read(bytes,offset,(int)Math.min(length,Math.max(1,limit-count+1))));
                    }
                    @Override public boolean isFinished() { return original.isFinished(); }
                    @Override public boolean isReady() { return original.isReady(); }
                    @Override public void setReadListener(ReadListener listener) { original.setReadListener(listener); }
                    @Override public void close() throws IOException { original.close(); }
                };
            }
            return stream;
        }
        @Override public BufferedReader getReader() throws IOException { return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8)); }
    }
}
