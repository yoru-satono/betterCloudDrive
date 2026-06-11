package com.betterclouddrive.web.webdav;

import com.betterclouddrive.dal.entity.FileEntity;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Component
public class PropFindXmlBuilder {

    private static final DateTimeFormatter RFC1123 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                    .withZone(ZoneId.of("GMT"));
    private static final DateTimeFormatter ISO8601 =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC"));

    public record DavResource(String href, FileEntity file) {}

    public String build(List<DavResource> resources) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<D:multistatus xmlns:D=\"DAV:\">\n");
        for (DavResource res : resources) {
            appendResponse(sb, res.href(), res.file());
        }
        sb.append("</D:multistatus>");
        return sb.toString();
    }

    private void appendResponse(StringBuilder sb, String href, FileEntity file) {
        sb.append("  <D:response>\n");
        sb.append("    <D:href>").append(escapeXml(href)).append("</D:href>\n");
        sb.append("    <D:propstat>\n");
        sb.append("      <D:prop>\n");
        sb.append("        <D:displayname>").append(escapeXml(file.getFileName())).append("</D:displayname>\n");

        if (file.getCreatedAt() != null) {
            String created = ISO8601.format(file.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant());
            sb.append("        <D:creationdate>").append(created).append("</D:creationdate>\n");
        }
        if (file.getUpdatedAt() != null) {
            String modified = RFC1123.format(file.getUpdatedAt().atZone(ZoneId.systemDefault()));
            sb.append("        <D:getlastmodified>").append(modified).append("</D:getlastmodified>\n");
        }

        boolean isFolder = "folder".equals(file.getFileType());
        if (isFolder) {
            sb.append("        <D:resourcetype><D:collection/></D:resourcetype>\n");
        } else {
            sb.append("        <D:resourcetype/>\n");
            if (file.getFileSize() != null) {
                sb.append("        <D:getcontentlength>").append(file.getFileSize()).append("</D:getcontentlength>\n");
            }
            String mime = file.getMimeType() != null ? file.getMimeType() : "application/octet-stream";
            sb.append("        <D:getcontenttype>").append(escapeXml(mime)).append("</D:getcontenttype>\n");
            if (file.getMd5Hash() != null) {
                sb.append("        <D:getetag>\"").append(escapeXml(file.getMd5Hash())).append("\"</D:getetag>\n");
            }
        }
        sb.append("      </D:prop>\n");
        sb.append("      <D:status>HTTP/1.1 200 OK</D:status>\n");
        sb.append("    </D:propstat>\n");
        sb.append("  </D:response>\n");
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
