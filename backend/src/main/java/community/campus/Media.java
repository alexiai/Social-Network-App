package community.campus;

import jakarta.annotation.PreDestroy;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.Principal;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@RestController
@RequestMapping("/api/v1/media")
class Media {
    private final MediaService media;
    Media(MediaService media) { this.media = media; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Map<String,String> upload(Principal actor, @RequestParam("file") MultipartFile file) throws IOException {
        return Map.of("url", media.save(actor.getName(), file));
    }

    @GetMapping("/{id}")
    ResponseEntity<byte[]> download(Principal actor, @PathVariable UUID id, @RequestParam(defaultValue="false") boolean thumb) {
        var file = media.load(actor.getName(), id, thumb);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .contentType(MediaType.parseMediaType(file.type()))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.builder(file.type().startsWith("image/") ? "inline" : "attachment")
                .filename(file.filename()).build().toString())
            .body(file.content());
    }
}

@Service
class MediaService {
    record File(String type, String filename, byte[] content, byte[] thumbnail) {}
    static final String PREFIX = "/api/v1/media/";
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private final Store store;
    private final StringRedisTemplate redis;
    private final String bucket;
    private final S3Client s3;

    MediaService(Store store, StringRedisTemplate redis, @Value("${app.media-bucket:}") String bucket,
                 @Value("${app.media-region:eu-central-1}") String region) {
        this.store = store;
        this.redis = redis;
        this.bucket = bucket;
        this.s3 = bucket.isBlank() ? null : S3Client.builder().region(Region.of(region))
            .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(15))).build();
    }

    String save(String username, MultipartFile upload) throws IOException {
        if (upload.isEmpty() || upload.getSize() > MAX_BYTES) {
            throw ApiErrors.invalid("Choose a JPEG, PNG or PDF of up to 5 MiB");
        }
        var limit = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n", Long.class);
        Long count = redis.execute(limit, java.util.List.of("campus:upload:" + username));
        if (count == null || count > 10) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Wait a moment before uploading again");
        }
        var file = decode(upload.getBytes(), upload.getOriginalFilename());
        UUID id = UUID.randomUUID();
        var stored = new java.util.ArrayList<String>();
        try {
            if (s3 != null) {
                put(id + "/original", file.content(), file.type());
                stored.add(id + "/original");
                if (file.thumbnail() != null) {
                    put(id + "/thumb", file.thumbnail(), file.type());
                    stored.add(id + "/thumb");
                }
            }
            store.write("insert into media(id,owner,content_type,filename,original,thumbnail) values (?,?,?,?,?,?)",
                id, username, file.type(), file.filename(), s3 == null ? file.content() : null, s3 == null ? file.thumbnail() : null);
        } catch (RuntimeException error) {
            for (String key : stored) {
                try { s3.deleteObject(request -> request.bucket(bucket).key(key)); }
                catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            }
            throw error;
        }
        return PREFIX + id;
    }

    String reference(String username, String url, boolean imageOnly) {
        if (url == null || !url.startsWith(PREFIX)) return Input.media(url);
        UUID id;
        try { id = UUID.fromString(url.substring(PREFIX.length())); }
        catch (IllegalArgumentException error) { throw ApiErrors.invalid("Invalid uploaded media reference"); }
        if (!store.exists("select 1 from media where id=? and owner=? and (not ? or content_type='image/jpeg')", id, username, imageOnly)) {
            throw ApiErrors.missing();
        }
        return PREFIX + id;
    }

    File load(String username, UUID id, boolean thumb) {
        String reference = PREFIX + id;
        if (!store.exists("select 1 from media where id=? and owner=?", id, username)
                && !store.exists("""
                    select 1 from messages m join participants p on p.conversation_id=m.conversation_id
                    where m.attachment_url=? and p.username=?
                    """, reference, username)
                && !store.exists("""
                    select 1 from accounts a where (a.avatar_url=? or a.cover_url=?) and
                      (not a.private_profile or exists(select 1 from connections c where c.accepted and
                        ((c.sender=a.username and c.recipient=?) or (c.recipient=a.username and c.sender=?))))
                    """, reference, reference, username, username)
                && !store.exists("""
                    select 1 from posts p join accounts a on a.username=p.author where p.image_url=? and
                    """ + FeedService.VISIBLE, reference, username, username, username, username)) throw ApiErrors.missing();
        var row = store.one("select content_type,filename,original,thumbnail from media where id=?", id);
        String type = (String) row.get("content_type");
        boolean useThumb = thumb && type.equals("image/jpeg");
        byte[] bytes = (byte[]) row.get(useThumb ? "thumbnail" : "original");
        if (bytes == null && s3 != null) {
            bytes = useThumb ? thumbnail(id) : s3.getObjectAsBytes(request -> request.bucket(bucket).key(id + "/original")).asByteArray();
        }
        if (bytes == null) throw ApiErrors.missing();
        return new File(type, (String) row.get("filename"), bytes, null);
    }

    private byte[] thumbnail(UUID id) {
        String key = "campus:thumbnail:" + id;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) return java.util.Base64.getDecoder().decode(cached);
        } catch (org.springframework.dao.DataAccessException | IllegalArgumentException ignored) {
            // Authorization already succeeded, so fetch the original thumbnail from storage.
        }
        byte[] image = s3.getObjectAsBytes(request -> request.bucket(bucket).key(id + "/thumb")).asByteArray();
        if (image.length <= 128 * 1024) {
            try {
                redis.opsForValue().set(key, java.util.Base64.getEncoder().encodeToString(image), Duration.ofMinutes(5));
            } catch (org.springframework.dao.DataAccessException ignored) {
                // Storage is authoritative.
            }
        }
        return image;
    }

    private void put(String key, byte[] bytes, String contentType) {
        s3.putObject(request -> request.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(bytes));
    }

    private File decode(byte[] bytes, String originalName) throws IOException {
        if (bytes.length >= 5 && new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) {
            String name = originalName == null ? "attachment.pdf" : originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
            return new File("application/pdf", name.substring(0, Math.min(100, name.length())), bytes, null);
        }
        try (var imageStream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(imageStream);
            if (!readers.hasNext()) throw ApiErrors.invalid("Unsupported image format");
            var reader = readers.next();
            try {
                if (!java.util.Set.of("jpeg","png").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) {
                    throw ApiErrors.invalid("Choose a JPEG or PNG image");
                }
                reader.setInput(imageStream);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > 16_000_000) {
                    throw ApiErrors.invalid("Images must be at most 16 megapixels");
                }
                var image = reader.read(0);
                return new File("image/jpeg", "image.jpg", jpeg(image, 1600), jpeg(image, 480));
            } finally {
                reader.dispose();
            }
        } catch (javax.imageio.IIOException error) {
            throw ApiErrors.invalid("The image is damaged or incomplete");
        }
    }

    private byte[] jpeg(BufferedImage source, int maxEdge) throws IOException {
        double scale = Math.min(1.0, maxEdge / (double) Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) (source.getWidth() * scale));
        int height = Math.max(1, (int) (source.getHeight() * scale));
        var scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, java.awt.Color.WHITE, null);
        } finally {
            graphics.dispose();
        }
        var output = new ByteArrayOutputStream();
        ImageIO.write(scaled, "jpeg", output);
        return output.toByteArray();
    }

    @PreDestroy
    void close() { if (s3 != null) s3.close(); }
}
