package com.campus.market.media;
import org.springframework.core.io.Resource;
public interface ImageObjectStore {
    void write(String id,byte[] bytes);
    Resource read(String id);
    void delete(String id);
}
