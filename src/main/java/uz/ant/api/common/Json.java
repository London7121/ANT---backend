package uz.ant.api.common;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** JSON yordamchisi (Jackson 3): matn ↔ daraxt. JSONB ustunlari shu orqali o'qiladi/yoziladi. */
@Component
public class Json {
    private final JsonMapper mapper;

    public Json(JsonMapper mapper) { this.mapper = mapper; }

    public JsonMapper mapper() { return mapper; }
    public JsonNode parse(String text) { return mapper.readTree(text); }
    public String write(Object value) { return mapper.writeValueAsString(value); }
    public ObjectNode object() { return mapper.createObjectNode(); }
}
