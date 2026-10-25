package com.campus.market.media;

import com.campus.market.auth.SessionService;
import com.campus.market.product.ProductForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

@Service
public class ImageService {
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(ImageService.class);
    private final ImageRepository repository;
    private final SessionService sessions;
    private final Path directory;
    private final ImageObjectStore store;
    private final ImageLibraryService library;
    private final com.campus.market.governance.WriteGovernance governance;

    public ImageService(ImageRepository repository, SessionService sessions,
                        @Value("${market.upload-dir:./data/uploads}") String directory,ImageObjectStore store,ImageLibraryService library,com.campus.market.governance.WriteGovernance governance) {
        this.store=store;this.library=library;this.governance=governance;
        this.repository = repository; this.sessions = sessions;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ImageResponse upload(MultipartFile file, String authorization) {
        var user = sessions.currentUser(authorization);
        governance.requireWritable(user.id(),"IMAGE");
        if (file == null || file.isEmpty()) throw new ImageValidationException("请选择图片");
        if (file.getSize() > MAX_BYTES) throw new ImageValidationException("图片不能超过5MB");
        BufferedImage image = decode(file);
        String id = UUID.randomUUID().toString();
        var output = new ByteArrayOutputStream();
        try {if (!ImageIO.write(image,"png",output))throw new IllegalStateException("无法编码图片");}
        catch(IOException ex){throw new ImageValidationException("图片处理失败");}
        if(output.size()>10*1024*1024)throw new ImageValidationException("图片处理后过大");
        byte[] bytes=output.toByteArray();library.reserve(user.id(),bytes.length);store.write(id,bytes);
        // 数据库事务回滚时，尽力清理刚写入的文件。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try { store.delete(id); }
                    catch (RuntimeException e) { log.warn("回滚后清理图片失败（{}）", e.getClass().getSimpleName()); }
                }
            }
        });
        repository.insert(id, user.id(), image.getWidth(), image.getHeight());
        library.record(id,bytes);
        return new ImageResponse(id, "/api/images/" + id, image.getWidth(), image.getHeight());
    }

    private BufferedImage decode(MultipartFile file) {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(file.getBytes()))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new ImageValidationException("只支持有效的PNG或JPG图片");
            var reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg")) {
                    throw new ImageValidationException("只支持PNG或JPG图片");
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 2000 || height > 2000) {
                    throw new ImageValidationException("图片宽高不能超过2000像素");
                }
                var decoded = reader.read(0);
                var normalized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                var graphics = normalized.createGraphics();
                try { graphics.drawImage(decoded, 0, 0, null); }
                finally { graphics.dispose(); }
                return normalized;
            } finally { reader.dispose(); }
        } catch (IOException e) {
            throw new ImageValidationException("图片内容损坏，无法读取");
        }
    }

    public String requireOwned(String id, long userId) {
        if (id == null || id.isBlank()) return null;
        if (!validId(id)) throw new ImageValidationException("图片ID格式不正确");
        long ownerId = repository.findOwnerForUpdate(id)
                .orElseThrow(() -> new ImageValidationException("图片不存在，请重新上传"));
        if (ownerId != userId) throw new ProductForbiddenException("只能使用自己上传的图片");
        return id;
    }

    public Resource read(String id) {
        if (!validId(id) || repository.findOwner(id).isEmpty()) throw new ImageNotFoundException();
        return store.read(id);
    }

    private boolean validId(String id) {
        return id != null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
