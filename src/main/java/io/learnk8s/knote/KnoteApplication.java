package io.learnk8s.knote;


import io.minio.MinioClient;
import io.minio.errors.InvalidEndpointException;
import io.minio.errors.InvalidPortException;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.commons.io.IOUtils;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@SpringBootApplication
public class KnoteApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnoteApplication.class, args);
    }

}

interface NotesRepository extends MongoRepository<Note, String> {

}

@Document(collection = "notes")
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
class Note {
    @Id
    private String id;
    private String description;

    @Override
    public String toString() {
        return description;
    }
}

@ConfigurationProperties(prefix = "knote")
class KnoteProperties {

    @Value("${minio.host:${MINIO_HOST:localhost}}")
    private String minioHost;

    @Value("${minio.bucket:${MINIO_BUCKET:image-storage}}")
    private String minioBucket;

    @Value("${minio.access.key:${MINIO_ACCESS_KEY:}}")
    private String minioAccessKey;

    @Value("${minio.secret.key:${MINIO_SECRET_KEY:}}")
    private String minioSecretKey;

    @Value("${minio.useSSL:${MINIO_USE_SSL:false}}")
    private boolean minioUseSSL;

    @Value("${minio.reconnect.enabled:${MINIO_RECONNECT_ENABLED:true}}")
    private boolean minioReconnectEnabled;

    public String getMinioHost() { return minioHost; }
    public String getMinioBucket() { return minioBucket; }
    public String getMinioAccessKey() { return minioAccessKey; }
    public String getMinioSecretKey() { return minioSecretKey; }
    public boolean isMinioUseSSL() { return minioUseSSL; }
    public boolean isMinioReconnectEnabled() { return minioReconnectEnabled; }
}

@Controller
@EnableConfigurationProperties(KnoteProperties.class)
class KNoteController {

    @Autowired
    private NotesRepository notesRepository;
    @Autowired
    private KnoteProperties properties;

    private MinioClient minioClient;

    private Parser parser = Parser.builder().build();
    private HtmlRenderer renderer = HtmlRenderer.builder().build();

    @PostConstruct
    public void init() {
        initMinio();
    }

    private void initMinio() {
        boolean initialized = false;
        while (!initialized) {
            try {
                minioClient = new MinioClient(
                        "http://" + properties.getMinioHost() + ":9000",
                        properties.getMinioAccessKey(),
                        properties.getMinioSecretKey(),
                        properties.isMinioUseSSL()
                );
                boolean bucketExists = minioClient.bucketExists(properties.getMinioBucket());
                if (!bucketExists) {
                    minioClient.makeBucket(properties.getMinioBucket());
                }
                System.out.println("> Minio initialized!");
                initialized = true;
            } catch (Exception e) {
                System.out.println("> Minio connection failed: " + e.getMessage());
                if (properties.isMinioReconnectEnabled()) {
                    System.out.println("> Retrying in 5 seconds...");
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    throw new RuntimeException("Failed to connect to Minio", e);
                }
            }
        }
    }

    @GetMapping("/")
    public String index(Model model) {
        getAllNotes(model);
        return "index";
    }

    @GetMapping(value = "/img/{name}", produces = MediaType.IMAGE_PNG_VALUE)
    @ResponseBody
    public byte[] getImageByName(@PathVariable String name) throws Exception {
        InputStream stream = minioClient.getObject(properties.getMinioBucket(), name);
        return IOUtils.toByteArray(stream);
    }

    @PostMapping("/note")
    public String saveNotes(@RequestParam("image") MultipartFile file,
                            @RequestParam String description,
                            @RequestParam(required = false) String publish,
                            @RequestParam(required = false) String upload,
                            Model model) throws Exception {

        if (publish != null && publish.equals("Publish")) {
            saveNote(description, model);
            getAllNotes(model);
            return "redirect:/";
        }
        if (upload != null && upload.equals("Upload")) {
            if (file != null && file.getOriginalFilename() != null &&
                    !file.getOriginalFilename().isEmpty()) {
                uploadImage(file, description, model);
            }
            getAllNotes(model);
            return "index";
        }
        return "index";
    }


    private void getAllNotes(Model model) {
        List<Note> notes = notesRepository.findAll();
        Collections.reverse(notes);
        model.addAttribute("notes", notes);
    }

    private void uploadImage(MultipartFile file, String description, Model model) throws Exception {
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.split("\\.")[1]
                : "png";
        String fileId = UUID.randomUUID().toString() + "." + extension;
        minioClient.putObject(
                properties.getMinioBucket(),
                fileId,
                file.getInputStream(),
                file.getSize(),
                null,
                null,
                file.getContentType()
        );
        model.addAttribute("description", description + " ![](/img/" + fileId + ")");
    }

    private void saveNote(String description, Model model) {
        if (description != null && !description.trim().isEmpty()) {
            //We need to translate markup to HTML
            Node document = parser.parse(description.trim());
            String html = renderer.render(document);
            notesRepository.save(new Note(null, html));
            //After publish you need to clean up the textarea
            model.addAttribute("description", "");
        }
    }

}
