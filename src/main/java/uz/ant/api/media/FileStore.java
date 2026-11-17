package uz.ant.api.media;

import java.io.IOException;
import java.nio.file.Path;

/** Yuklangan fayllar ombori (lokal disk yoki S3 mos bulut). Fayl nomi — ombor ichidagi kalit. */
public interface FileStore {
    /** `file` ni omborga joylaydi. Lokal omborda fayl ko'chiriladi (manba yo'qoladi), bulutda nusxalanadi. */
    void put(String name, Path file, String contentType) throws IOException;

    /** Faylni o'chiradi (yo'q bo'lsa — xato emas). */
    void delete(String name) throws IOException;

    /** Brauzer fayl ochadigan manzil: lokalda `/uploads/<name>`, bulutda ommaviy URL. */
    String url(String name);
}
