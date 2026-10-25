package com.campus.market.media;
import java.nio.file.*;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.stereotype.Component;
@Component
public class LocalImageObjectStore implements ImageObjectStore {
    private final Path root;
    @org.springframework.beans.factory.annotation.Autowired private javax.sql.DataSource dataSource;
    public LocalImageObjectStore(@Value("${market.upload-dir:./data/uploads}")String directory){root=Path.of(directory).toAbsolutePath().normalize();}
    private Path path(String id)throws IOException {
        if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new ImageValidationException("图片编号无效");
        Files.createDirectories(root);
        Path file=root.resolve(id+".png");
        if(Files.isSymbolicLink(file)||Files.isSymbolicLink(root)||!root.toRealPath().resolve(id+".png").normalize().startsWith(root.toRealPath()))throw new IOException("Unsafe image path");
        return file;
    }
    public void write(String id,byte[] bytes){
        Path file=null;boolean created=false;
        try{file=path(id);try(var output=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)){created=true;output.write(bytes);}}
        catch(IOException ex){if(created&&file!=null)try{Files.deleteIfExists(file);}catch(IOException cleanup){ex.addSuppressed(cleanup);}throw new IllegalStateException("保存图片失败",ex);}
    }
    public Resource read(String id){try{Path file=path(id);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new ImageNotFoundException();return new FileSystemResource(file);}catch(IOException ex){throw new ImageNotFoundException();}}
    public void delete(String id){
        try(var connection=dataSource.getConnection()) {
            String name="campus-images-"+connection.getCatalog();
            try(var lock=connection.prepareStatement("SELECT GET_LOCK(?,0)")) {
                lock.setString(1,name);
                try(var rows=lock.executeQuery()){if(!rows.next()||rows.getInt(1)!=1)throw new IllegalStateException("图片备份中，物理清理稍后重试");}
            }
            try{Files.deleteIfExists(path(id));}
            finally{try(var release=connection.prepareStatement("SELECT RELEASE_LOCK(?)")){release.setString(1,name);release.execute();}}
        }catch(java.sql.SQLException|IOException ex){throw new IllegalStateException("清理图片失败",ex);}
    }
}
