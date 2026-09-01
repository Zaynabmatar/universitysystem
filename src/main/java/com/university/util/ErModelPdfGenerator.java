package com.university.util;

import com.lowagie.text.Document;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.pdf.PdfWriter;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ErModelPdfGenerator {

    private static final int WIDTH = 2800;
    private static final int HEIGHT = 2000;

    private static final int MARGIN = 55;
    private static final int TOP = 150;
    private static final int BOTTOM_RESERVED = 360;
    private static final int GAP_X = 90;
    private static final int GAP_Y = 75;
    private static final int COLS = 5;

    private static final Font TITLE_FONT =
            new Font("Arial", Font.BOLD, 42);

    private static final Font SUBTITLE_FONT =
            new Font("Arial", Font.PLAIN, 20);

    private static final Font ENTITY_FONT =
            new Font("Arial", Font.BOLD, 17);

    private static final Font ATTRIBUTE_FONT =
            new Font("Consolas", Font.PLAIN, 16);

    private static final Font RELATION_FONT =
            new Font("Arial", Font.BOLD, 28);

    private static class Entity {
        String name;
        List<String> attributes = new ArrayList<>();
        int page;

        Entity(String name) {
            this.name = name;
        }
    }

    private static class Relation {
        String left;
        String operator;
        String right;
        String label;

        Relation(
                String left,
                String operator,
                String right,
                String label
        ) {
            this.left = left;
            this.operator = operator;
            this.right = right;
            this.label = label;
        }

        String display() {
            return left + " " + operator + " " +
                    right + " : " + label;
        }
    }

    private static class Box {
        Entity entity;
        int x;
        int y;
        int width;
        int height;

        Box(Entity entity, int x, int y, int width, int height) {
            this.entity = entity;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        int centerX() {
            return x + width / 2;
        }

        int centerY() {
            return y + height / 2;
        }
    }

    public static void main(String[] args) throws Exception {

        Path input = Path.of("corrected_er_diagram.mmd");

        if (!Files.exists(input)) {
            throw new IllegalStateException(
                    "corrected_er_diagram.mmd was not found."
            );
        }

        Path outputDir = Path.of("docs", "er-model");
        Files.createDirectories(outputDir);

        List<String> lines =
                Files.readAllLines(input, StandardCharsets.UTF_8);

        LinkedHashMap<String, Entity> entities =
                parseEntities(lines);

        List<Relation> relations =
                parseRelations(lines);

        assignPages(entities);

        System.out.println();
        System.out.println("======================================");
        System.out.println("UNIVERSITY ER MODEL GENERATOR");
        System.out.println("======================================");
        System.out.println("Entities found     : " + entities.size());
        System.out.println("Relationships found: " + relations.size());
        System.out.println();

        List<Path> generatedImages = new ArrayList<>();

        for (int page = 1; page <= 3; page++) {

            Path png = outputDir.resolve(
                    "ER_Model_Page_" + page + ".png"
            );

            renderPage(
                    page,
                    entities,
                    relations,
                    png
            );

            generatedImages.add(png);

            System.out.println(
                    "Created: " + png.toAbsolutePath()
            );
        }

        Path pdf = outputDir.resolve(
                "University_System_ER_Model_3_Pages.pdf"
        );

        createPdf(generatedImages, pdf);

        System.out.println();
        System.out.println("======================================");
        System.out.println("DONE");
        System.out.println("======================================");
        System.out.println(
                "PDF: " + pdf.toAbsolutePath()
        );
        System.out.println();
    }

    private static LinkedHashMap<String, Entity> parseEntities(
            List<String> lines
    ) {

        LinkedHashMap<String, Entity> entities =
                new LinkedHashMap<>();

        Pattern entityStart = Pattern.compile(
                "^\\s*([A-Z][A-Z0-9_]*)\\s*\\{\\s*$"
        );

        Entity current = null;

        for (String line : lines) {

            Matcher matcher = entityStart.matcher(line);

            if (matcher.matches()) {
                current = new Entity(matcher.group(1));
                entities.put(current.name, current);
                continue;
            }

            if (current != null) {

                String trimmed = line.trim();

                if (trimmed.equals("}")) {
                    current = null;
                    continue;
                }

                if (!trimmed.isBlank()
                        && !trimmed.startsWith("%%")) {
                    current.attributes.add(trimmed);
                }
            }
        }

        return entities;
    }

    private static List<Relation> parseRelations(
            List<String> lines
    ) {

        List<Relation> relations = new ArrayList<>();

        Pattern relationPattern = Pattern.compile(
                "^\\s*([A-Z][A-Z0-9_]*)\\s+" +
                "([^\\s]+)\\s+" +
                "([A-Z][A-Z0-9_]*)\\s*:\\s*(.+?)\\s*$"
        );

        for (String line : lines) {

            Matcher matcher =
                    relationPattern.matcher(line);

            if (matcher.matches()) {

                relations.add(
                        new Relation(
                                matcher.group(1),
                                matcher.group(2),
                                matcher.group(3),
                                matcher.group(4)
                        )
                );
            }
        }

        return relations;
    }

    private static void assignPages(
            LinkedHashMap<String, Entity> entities
    ) {

        Set<String> page1 = Set.of(
                "DEPARTMENTS",
                "CAMPUSES",
                "PROGRAMS",
                "PROGRAM_REQUIREMENTS",
                "USERS",
                "STUDENTS",
                "INSTRUCTORS",
                "COURSES",
                "COURSE_PREREQUISITES",
                "SEMESTERS",
                "SECTIONS",
                "SECTION_SCHEDULES"
        );

        Set<String> page2 = Set.of(
                "ENROLLMENTS",
                "GRADES",
                "EXAMS",
                "ATTENDANCE_RECORDS",
                "INSTRUCTOR_EVALUATIONS",
                "EVALUATION_QUESTIONS",
                "EVALUATION_ANSWERS",
                "CALENDAR_EVENTS",
                "STUDENT_SERVICE_REQUESTS"
        );

        Set<String> page3 = Set.of(
                "FEE_TYPES",
                "STUDENT_INVOICES",
                "INVOICE_ITEMS",
                "PAYMENTS",
                "FINANCIAL_TRANSACTIONS",
                "TUITION_RATES",
                "STUDENT_TUITION_INSTALLMENTS",
                "NOTIFICATIONS",
                "AUDIT_LOG"
        );

        int fallback = 1;

        for (Entity entity : entities.values()) {

            if (page1.contains(entity.name)) {
                entity.page = 1;
            } else if (page2.contains(entity.name)) {
                entity.page = 2;
            } else if (page3.contains(entity.name)) {
                entity.page = 3;
            } else {
                entity.page = fallback;
                fallback++;
                if (fallback > 3) {
                    fallback = 1;
                }
            }
        }
    }

    private static void renderPage(
            int page,
            LinkedHashMap<String, Entity> entities,
            List<Relation> relations,
            Path output
    ) throws Exception {

        BufferedImage image =
                new BufferedImage(
                        WIDTH,
                        HEIGHT,
                        BufferedImage.TYPE_INT_RGB
                );

        Graphics2D g = image.createGraphics();

        g.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
        );

        g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
        );

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        drawHeader(g, page);
        LinkedHashMap<String, Entity> pageEntityMap = new LinkedHashMap<>();

        // Original tables for this page - full attributes
        for (Entity e : entities.values()) {
            if (e.page == page) {
                pageEntityMap.put(e.name, e);
            }
        }

        // Small PK-only references for relationships to other pages
        for (Relation r : relations) {
            Entity left = entities.get(r.left);
            Entity right = entities.get(r.right);

            if (left == null || right == null) {
                continue;
            }

            if (left.page == page && right.page < page) {
                pageEntityMap.putIfAbsent(
                        right.name,
                        compactReference(right)
                );
            }

            if (right.page == page && left.page < page) {
                pageEntityMap.putIfAbsent(
                        left.name,
                        compactReference(left)
                );
            }
        }

        List<Entity> pageEntities =
                new ArrayList<>(pageEntityMap.values());

        Map<String, Box> boxes;

        if (page == 1) {
            boxes = calculatePage1Layout(pageEntities);
        } else if (page == 2) {
            boxes = calculatePage2Layout(pageEntities);
        } else if (page == 3) {
            boxes = calculatePage3Layout(pageEntities);
        } else {
            boxes = calculateLayout(pageEntities);
        }

        drawSamePageRelations(
                g,
                page,
                relations,
                entities,
                boxes
        );

        for (Box box : boxes.values()) {
            drawEntityBox(g, box);
        }


        drawLegend(g);

        g.dispose();

        ImageIO.write(
                image,
                "png",
                output.toFile()
        );
    }


    private static Entity compactReference(Entity original) {

        Entity ref = new Entity(original.name);
        ref.page = original.page;

        return ref;
    }

    private static Map<String, Box> calculatePage1Layout(
            List<Entity> entities
    ) {

        LinkedHashMap<String, Box> boxes = new LinkedHashMap<>();
        Map<String, Entity> e = new HashMap<>();

        for (Entity entity : entities) {
            e.put(entity.name, entity);
        }

        int w = 430;

        String[] names = {
                "USERS",
                "DEPARTMENTS",
                "PROGRAMS",
                "COURSES",
                "CAMPUSES",
                "SEMESTERS",
                "INSTRUCTORS",
                "STUDENTS",
                "PROGRAM_REQUIREMENTS",
                "SECTIONS",
                "COURSE_PREREQUISITES",
                "SECTION_SCHEDULES"
        };

        int[][] pos = {
                {60, 300},
                {510, 300},
                {960, 300},
                {1410, 300},
                {1860, 300},
                {2310, 300},

                {280, 920},
                {730, 920},
                {1180, 920},
                {1860, 920},

                {1410, 1500},
                {1860, 1500}
        };

        for (int i = 0; i < names.length; i++) {

            Entity entity = e.get(names[i]);

            if (entity == null) {
                continue;
            }

            int h =
                    36 +
                    (entity.attributes.size() * 18) +
                    12;

            boxes.put(
                    entity.name,
                    new Box(
                            entity,
                            pos[i][0],
                            pos[i][1],
                            w,
                            h
                    )
            );
        }

        return boxes;
    }
    private static Map<String, Box> calculatePage2Layout(
            List<Entity> entities
    ) {
        LinkedHashMap<String, Box> boxes = new LinkedHashMap<>();
        Map<String, Entity> e = new HashMap<>();

        for (Entity entity : entities) {
            e.put(entity.name, entity);
        }

        int fullW = 420;
        int refW = 260;

        String[] names = {
                "ENROLLMENTS",
                "GRADES",
                "EXAMS",
                "CALENDAR_EVENTS",

                "ATTENDANCE_RECORDS",
                "INSTRUCTOR_EVALUATIONS",
                "EVALUATION_QUESTIONS",
                "EVALUATION_ANSWERS",

                "STUDENT_SERVICE_REQUESTS",

                "STUDENTS",
                "SECTIONS",
                "USERS",
                "SEMESTERS"
        };

        int[][] pos = {
                {180,180}, {820,180}, {1460,180}, {2100,180},

                {180,800}, {820,800}, {1460,800}, {2100,800},

                {180,1250},

                {260,1500}, {900,1500}, {1540,1500}, {2180,1500}
        };

        for (int i = 0; i < names.length; i++) {
            Entity entity = e.get(names[i]);

            if (entity == null) {
                continue;
            }

            boolean reference = entity.attributes.isEmpty();

            int w = reference ? refW : fullW;
            int h = 36 + (entity.attributes.size() * 18) + 12;

            boxes.put(
                    entity.name,
                    new Box(
                            entity,
                            pos[i][0],
                            pos[i][1],
                            w,
                            h
                    )
            );
        }

        return boxes;
    }
    private static Map<String, Box> calculatePage3Layout(
            List<Entity> entities
    ) {
        LinkedHashMap<String, Box> boxes = new LinkedHashMap<>();
        Map<String, Entity> e = new HashMap<>();

        for (Entity entity : entities) {
            e.put(entity.name, entity);
        }

        int fullW = 420;
        int refW = 260;

        String[] names = {
                "FEE_TYPES",
                "STUDENT_INVOICES",
                "PAYMENTS",
                "FINANCIAL_TRANSACTIONS",
                "TUITION_RATES",

                "INVOICE_ITEMS",
                "STUDENT_TUITION_INSTALLMENTS",
                "NOTIFICATIONS",
                "AUDIT_LOG",

                "STUDENTS",
                "SEMESTERS",
                "USERS"
        };

        int[][] pos = {
                // FEE_TYPES
                {40,180},

                // STUDENT_INVOICES
                {1190,180},

                // PAYMENTS
                {1765,180},

                // FINANCIAL_TRANSACTIONS
                {2340,180},

                // TUITION_RATES
                {280,760},

                // INVOICE_ITEMS
                {615,180},

                // STUDENT_TUITION_INSTALLMENTS
                {900,820},

                // NOTIFICATIONS
                {1840,820},

                // AUDIT_LOG
                {2320,820},

                // STUDENTS reference
                {1240,1510},

                // SEMESTERS reference
                {560,1480},

                // USERS reference
                {2240,1510}
        };

        for (int i = 0; i < names.length; i++) {
            Entity entity = e.get(names[i]);

            if (entity == null) {
                continue;
            }

            boolean reference = entity.attributes.isEmpty();

            int w = reference ? refW : fullW;
            int h = 36 + (entity.attributes.size() * 18) + 12;

            boxes.put(
                    entity.name,
                    new Box(
                            entity,
                            pos[i][0],
                            pos[i][1],
                            w,
                            h
                    )
            );
        }

        return boxes;
    }
    private static Map<String, Box> calculateLayout(
            List<Entity> entities
    ) {

        LinkedHashMap<String, Box> boxes =
                new LinkedHashMap<>();

        int availableWidth =
                WIDTH - (2 * MARGIN);

        int colWidth =
                (availableWidth -
                        ((COLS - 1) * GAP_X))
                        / COLS;

        List<Entity> ordered =
                new ArrayList<>(entities);

        ordered.sort(
                Comparator.comparingInt(
                        (Entity e) -> e.attributes.size()
                ).reversed()
        );

        int rows =
                (int) Math.ceil(
                        ordered.size() / (double) COLS
                );

        int[] boxHeights =
                new int[ordered.size()];

        int[] rowHeights =
                new int[rows];

        for (int i = 0; i < ordered.size(); i++) {

            int h =
                    36 +
                    (ordered.get(i).attributes.size() * 18) +
                    12;

            boxHeights[i] = h;

            int row = i / COLS;

            rowHeights[row] =
                    Math.max(rowHeights[row], h);
        }

        int contentTop = TOP;
        int contentBottom = HEIGHT - 130;

        int totalHeights = 0;

        for (int h : rowHeights) {
            totalHeights += h;
        }

        int freeSpace =
                Math.max(
                        0,
                        contentBottom -
                        contentTop -
                        totalHeights
                );

        int rowGap =
                rows > 1
                        ? freeSpace / (rows - 1)
                        : 0;

        int y = contentTop;

        for (int row = 0; row < rows; row++) {

            int start = row * COLS;

            int count =
                    Math.min(
                            COLS,
                            ordered.size() - start
                    );

            int totalRowWidth =
                    (count * colWidth) +
                    ((count - 1) * GAP_X);

            int startX =
                    MARGIN +
                    Math.max(
                            0,
                            (availableWidth - totalRowWidth) / 2
                    );

            for (int column = 0;
                 column < count;
                 column++) {

                int index = start + column;

                Entity entity =
                        ordered.get(index);

                int x =
                        startX +
                        column * (colWidth + GAP_X);

                Box box =
                        new Box(
                                entity,
                                x,
                                y,
                                colWidth,
                                boxHeights[index]
                        );

                boxes.put(
                        entity.name,
                        box
                );
            }

            y += rowHeights[row] + rowGap;
        }

        return boxes;
    }
    private static void drawHeader(
            Graphics2D g,
            int page
    ) {

        g.setColor(new Color(20, 33, 61));

        g.setFont(TITLE_FONT);

        g.drawString(
                "University Registration System - ER Model",
                MARGIN,
                62
        );

        g.setFont(SUBTITLE_FONT);

        String subtitle = switch (page) {
            case 1 ->
                    "Page 1 of 3 - Core Academic Structure";
            case 2 ->
                    "Page 2 of 3 - Enrollment, Assessment & Evaluation";
            default ->
                    "Page 3 of 3 - Finance, Notifications & Audit";
        };

        g.drawString(
                subtitle,
                MARGIN,
                100
        );

        g.drawLine(
                MARGIN,
                120,
                WIDTH - MARGIN,
                120
        );
    }

    private static void drawEntityBox(
            Graphics2D g,
            Box box
    ) {

        int headerHeight = 34;

        g.setColor(Color.WHITE);

        g.fillRoundRect(
                box.x,
                box.y,
                box.width,
                box.height,
                14,
                14
        );

        g.setColor(new Color(31, 56, 100));

        g.fillRoundRect(
                box.x,
                box.y,
                box.width,
                headerHeight,
                14,
                14
        );

        g.fillRect(
                box.x,
                box.y + headerHeight - 12,
                box.width,
                12
        );

        g.setColor(new Color(40, 40, 40));

        g.setStroke(new BasicStroke(2));

        g.drawRoundRect(
                box.x,
                box.y,
                box.width,
                box.height,
                14,
                14
        );

        g.setColor(Color.WHITE);
        g.setFont(ENTITY_FONT);

        FontMetrics titleMetrics =
                g.getFontMetrics();

        int titleWidth =
                titleMetrics.stringWidth(
                        box.entity.name
                );

        g.drawString(
                box.entity.name,
                box.x +
                        (box.width - titleWidth) / 2,
                box.y + 31
        );

        g.setFont(ATTRIBUTE_FONT);

        int y =
                box.y + headerHeight + 23;

        for (String attribute :
                box.entity.attributes) {

            String display =
                    shorten(
                            attribute,
                            box.width,
                            g
                    );

            if (attribute.contains(" PK")
                    || attribute.endsWith("PK")) {

                g.setColor(
                        new Color(145, 35, 35)
                );

            } else if (attribute.contains("FK")) {

                g.setColor(
                        new Color(25, 86, 135)
                );

            } else if (attribute.contains("UK")) {

                g.setColor(
                        new Color(100, 70, 15)
                );

            } else {

                g.setColor(
                        new Color(35, 35, 35)
                );
            }

            g.drawString(
                    display,
                    box.x + 14,
                    y
            );

            y += 18;
        }
    }

    private static String shorten(
            String text,
            int boxWidth,
            Graphics2D g
    ) {

        FontMetrics fm =
                g.getFontMetrics(ATTRIBUTE_FONT);

        int maxWidth =
                boxWidth - 28;

        if (fm.stringWidth(text) <= maxWidth) {
            return text;
        }

        String result = text;

        while (
                result.length() > 3
                        && fm.stringWidth(
                        result + "..."
                ) > maxWidth
        ) {
            result =
                    result.substring(
                            0,
                            result.length() - 1
                    );
        }

        return result + "...";
    }


    private static int[] pointOnBoxEdge(
            Box box,
            int targetX,
            int targetY
    ) {
        double cx = box.centerX();
        double cy = box.centerY();

        double dx = targetX - cx;
        double dy = targetY - cy;

        if (dx == 0 && dy == 0) {
            return new int[] {
                    box.centerX(),
                    box.centerY()
            };
        }

        double halfW = box.width / 2.0;
        double halfH = box.height / 2.0;

        double scaleX =
                dx == 0
                        ? Double.POSITIVE_INFINITY
                        : halfW / Math.abs(dx);

        double scaleY =
                dy == 0
                        ? Double.POSITIVE_INFINITY
                        : halfH / Math.abs(dy);

        double scale = Math.min(scaleX, scaleY);

        return new int[] {
                (int) Math.round(cx + dx * scale),
                (int) Math.round(cy + dy * scale)
        };
    }
    private static void drawSamePageRelations(
            Graphics2D g,
            int page,
            List<Relation> relations,
            Map<String, Entity> entities,
            Map<String, Box> boxes
    ) {

        g.setStroke(
                new BasicStroke(page == 3 ? 3.2f : 4.5f,
                        BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND
                )
        );

        g.setFont((page == 2 || page == 3)
                ? new Font("Arial", Font.BOLD, 20)
                : RELATION_FONT);

        Set<String> drawnPage3Pairs = new HashSet<>();

        for (Relation relation : relations) {

            // EARLY OVERRIDE PREREQ TWO STRAIGHT LINES
            // These two relations are handled here first,
            // so older drawing code below cannot draw them again.

            if (page == 1 &&
                    relation.left.equals("COURSES") &&
                    relation.right.equals("COURSE_PREREQUISITES") &&
                    relation.label.equals("has_prerequisites")) {

                Box courseBox =
                        boxes.get("COURSES");

                Box prereqBox =
                        boxes.get("COURSE_PREREQUISITES");

                // FIRST straight vertical line
                int lineX =
                        courseBox.x + 265;

                int startY =
                        courseBox.y + courseBox.height;

                int endY =
                        prereqBox.y;

                g.setColor(new Color(110,110,110,170));

                g.draw(
                        new Line2D.Double(
                                lineX,
                                startY,
                                lineX,
                                endY
                        )
                );

                drawArrowHead(
                        g,
                        lineX,
                        startY,
                        lineX,
                        endY
                );

                String txt =
                        "1 : M has_prerequisites";

                FontMetrics fm =
                        g.getFontMetrics();

                // Lower label, stuck beside its own line
                int txtX =
                        lineX -
                        fm.stringWidth(txt) -
                        12;

                int txtY =
                        endY - 135;

                g.setColor(new Color(255,255,255,235));

                g.fillRect(
                        txtX - 7,
                        txtY - fm.getAscent(),
                        fm.stringWidth(txt) + 14,
                        fm.getHeight()
                );

                g.setColor(new Color(70,70,70));

                g.drawString(
                        txt,
                        txtX,
                        txtY
                );

                continue;
            }


            if (page == 1 &&
                    relation.left.equals("COURSES") &&
                    relation.right.equals("COURSE_PREREQUISITES") &&
                    relation.label.equals("is_prerequisite_for")) {

                Box courseBox =
                        boxes.get("COURSES");

                Box prereqBox =
                        boxes.get("COURSE_PREREQUISITES");

                // SECOND straight vertical line
                int lineX =
                        courseBox.x + 290;

                int startY =
                        courseBox.y + courseBox.height;

                int endY =
                        prereqBox.y;

                g.setColor(new Color(110,110,110,170));

                g.draw(
                        new Line2D.Double(
                                lineX,
                                startY,
                                lineX,
                                endY
                        )
                );

                drawArrowHead(
                        g,
                        lineX,
                        startY,
                        lineX,
                        endY
                );

                String txt =
                        "1 : M is_prerequisite_for";

                FontMetrics fm =
                        g.getFontMetrics();

                // Lower label, stuck beside its own line
                int txtX =
                        lineX + 12;

                int txtY =
                        endY - 70;

                g.setColor(new Color(255,255,255,235));

                g.fillRect(
                        txtX - 7,
                        txtY - fm.getAscent(),
                        fm.stringWidth(txt) + 14,
                        fm.getHeight()
                );

                g.setColor(new Color(70,70,70));

                g.drawString(
                        txt,
                        txtX,
                        txtY
                );

                continue;
            }


            Entity left =
                    entities.get(relation.left);

            Entity right =
                    entities.get(relation.right);

            if (left == null || right == null) {
                continue;
            }

            if (!boxes.containsKey(left.name)
                    || !boxes.containsKey(right.name)) {
                continue;
            }

            // On later pages, do not draw relationships that belong
            // entirely to earlier pages just because both reference
            // boxes happen to be visible.
            if (page > 1
                    && left.page < page
                    && right.page < page) {
                continue;
            }

            // Page 3: draw only one visible connector between
            // the same pair of tables.
            if (page == 3) {
                String pairKey =
                        left.name.compareTo(right.name) <= 0
                                ? left.name + "|" + right.name
                                : right.name + "|" + left.name;

                if (!drawnPage3Pairs.add(pairKey)) {
                    continue;
                }
            }

            Box a = boxes.get(left.name);
            Box b = boxes.get(right.name);

            if (a == null || b == null) {
                continue;
            }

            int x1 = a.centerX();
            int y1 = a.centerY();
            int x2 = b.centerX();
            int y2 = b.centerY();

            // Draw every relationship from table edge to table edge
            // so the arrow head remains visible on Pages 1, 2 and 3.
            if (page >= 1 && page <= 3) {
                int[] start =
                        pointOnBoxEdge(
                                a,
                                b.centerX(),
                                b.centerY()
                        );

                int[] end =
                        pointOnBoxEdge(
                                b,
                                a.centerX(),
                                a.centerY()
                        );

                x1 = start[0];
                y1 = start[1];

                x2 = end[0];
                y2 = end[1];
            }

            g.setColor(
                    new Color(
                            110,
                            110,
                            110,
                            150
                    )
            );
            // Page 2: ONLY the new routed STUDENTS -> ENROLLMENTS line
            if (page == 2 && ((left.name.equals("STUDENTS") && right.name.equals("ENROLLMENTS")) || (left.name.equals("ENROLLMENTS") && right.name.equals("STUDENTS")))) {
                Box studentEnrollBox = boxes.get("STUDENTS");
                Box enrollmentBox = boxes.get("ENROLLMENTS");
                int sxSE = studentEnrollBox.x;
                int sySE = studentEnrollBox.centerY();
                int exSE = enrollmentBox.x;
                int eySE = enrollmentBox.centerY();
                int routeXSE = 95;
                g.draw(new Line2D.Double(sxSE, sySE, routeXSE, sySE));
                g.draw(new Line2D.Double(routeXSE, sySE, routeXSE, eySE));
                g.draw(new Line2D.Double(routeXSE, eySE, exSE, eySE));
                drawArrowHead(g, routeXSE, eySE, exSE, eySE);

                String studentEnrollLabel = "1 : M makes";
                FontMetrics studentEnrollFm = g.getFontMetrics();
                int studentEnrollLabelX = 145;
                int studentEnrollLabelY = 1080;
                g.setColor(new Color(255,255,255,230));
                g.fillRect(studentEnrollLabelX - 6, studentEnrollLabelY - studentEnrollFm.getAscent(), studentEnrollFm.stringWidth(studentEnrollLabel) + 12, studentEnrollFm.getHeight());
                g.setColor(new Color(70,70,70));
                g.drawString(studentEnrollLabel, studentEnrollLabelX, studentEnrollLabelY);

                continue;
            } else             // Page 3: route STUDENTS -> STUDENT_INVOICES
            // to the right of STUDENT_TUITION_INSTALLMENTS.
            // Page 1: DEPARTMENTS -> PROGRAMS custom route
            if (page == 1 && left.name.equals("DEPARTMENTS") && right.name.equals("PROGRAMS")) {
                Box dOffer = boxes.get("DEPARTMENTS");
                Box pOffer = boxes.get("PROGRAMS");
                int sxOffer = dOffer.x + dOffer.width - 55;
                int syOffer = dOffer.y;
                int exOffer = pOffer.x + 55;
                int eyOffer = pOffer.y;
                int yOffer = 255;
                g.setColor(new Color(110,110,110,150));
                g.draw(new Line2D.Double(sxOffer,syOffer,sxOffer,yOffer));
                g.draw(new Line2D.Double(sxOffer,yOffer,exOffer,yOffer));
                g.draw(new Line2D.Double(exOffer,yOffer,exOffer,eyOffer));
                drawArrowHead(g,exOffer,yOffer,exOffer,eyOffer);
                String offerTxt = "1 : M offers";
                FontMetrics offerFm = g.getFontMetrics();
                int offerX = ((sxOffer + exOffer) / 2) - (offerFm.stringWidth(offerTxt) / 2);
                int offerY = yOffer - 14;
                g.setColor(new Color(255,255,255,230));
                g.fillRect(offerX-6,offerY-24,offerFm.stringWidth(offerTxt)+12,32);
                g.setColor(new Color(70,70,70));
                g.drawString(offerTxt,offerX,offerY);
                continue;
            }
            // Page 1: route DEPARTMENTS -> COURSES above PROGRAMS
            if (page == 1 && left.name.equals("DEPARTMENTS") && right.name.equals("COURSES")) {
                Box deptBox = boxes.get("DEPARTMENTS");
                Box courseBox = boxes.get("COURSES");
                int sxOwns = deptBox.centerX();
                int syOwns = deptBox.y;
                int exOwns = courseBox.centerX();
                int eyOwns = courseBox.y;
                int topYOwns = 210;
                g.draw(new Line2D.Double(sxOwns, syOwns, sxOwns, topYOwns));
                g.draw(new Line2D.Double(sxOwns, topYOwns, exOwns, topYOwns));
                g.draw(new Line2D.Double(exOwns, topYOwns, exOwns, eyOwns));
                drawArrowHead(g, exOwns, topYOwns, exOwns, eyOwns);

                String ownsText = "1 : M owns";
                FontMetrics ownsFm = g.getFontMetrics();
                int ownsTextX =
                        ((sxOwns + exOwns) / 2) -
                        (ownsFm.stringWidth(ownsText) / 2);
                int ownsTextY = topYOwns - 18;

                g.setColor(new Color(255,255,255,230));
                g.fillRect(
                        ownsTextX - 8,
                        ownsTextY - 24,
                        ownsFm.stringWidth(ownsText) + 16,
                        32
                );

                g.setColor(new Color(70,70,70));
                g.drawString(
                        ownsText,
                        ownsTextX,
                        ownsTextY
                );

                continue;
            } else 
            // Page 1: route INSTRUCTORS -> SECTIONS underneath
            // STUDENTS, PROGRAM_REQUIREMENTS, COURSE_PREREQUISITES
            // and SECTION_SCHEDULES, then rise back up to SECTIONS.
            if (page == 1 &&
                    ((left.name.equals("INSTRUCTORS") && right.name.equals("SECTIONS")) ||
                     (left.name.equals("SECTIONS") && right.name.equals("INSTRUCTORS")))) {

                Box instructorBox = boxes.get("INSTRUCTORS");
                Box sectionBox = boxes.get("SECTIONS");

                int sx = instructorBox.centerX();
                int sy = instructorBox.y + instructorBox.height;

                // Enter SECTIONS from a separate point on its bottom edge,
                // away from the SECTIONS -> SECTION_SCHEDULES line.
                int entryX = sectionBox.x + sectionBox.width - 70;
                int ey = sectionBox.y + sectionBox.height;

                int routeY = 1740;
                int detourX = 2380;
                int bridgeY = 1260;

                g.draw(new Line2D.Double(
                        sx, sy,
                        sx, routeY
                ));

                g.draw(new Line2D.Double(
                        sx, routeY,
                        detourX, routeY
                ));

                g.draw(new Line2D.Double(
                        detourX, routeY,
                        detourX, bridgeY
                ));

                g.draw(new Line2D.Double(
                        detourX, bridgeY,
                        entryX, bridgeY
                ));

                // Separate vertical rise into SECTIONS
                g.draw(new Line2D.Double(
                        entryX, bridgeY,
                        entryX, ey
                ));

                drawArrowHead(
                        g,
                        entryX,
                        bridgeY,
                        entryX,
                        ey
                );

            } else if (page == 3 && relation.label.equals("billed")) {

                int billedRouteX = 1450;
                int billedRouteY = 600;

                g.draw(new Line2D.Double(
                        x1, y1,
                        billedRouteX, y1
                ));

                g.draw(new Line2D.Double(
                        billedRouteX, y1,
                        billedRouteX, billedRouteY
                ));

                g.draw(new Line2D.Double(
                        billedRouteX, billedRouteY,
                        x2, billedRouteY
                ));

                g.draw(new Line2D.Double(
                        x2, billedRouteY,
                        x2, y2
                ));

                drawArrowHead(
                        g,
                        x2,
                        billedRouteY,
                        x2,
                        y2
                );

            // Page 3: route billed_for above STUDENT_TUITION_INSTALLMENTS
            // so the connector and its label are never hidden by the table.
            } else if (page == 3 && relation.label.equals("billed_for")) {

                int routeY = 690;
                int routeX = x1 + 120;

                g.draw(new Line2D.Double(
                        x1, y1,
                        routeX, y1
                ));

                g.draw(new Line2D.Double(
                        routeX, y1,
                        routeX, routeY
                ));

                g.draw(new Line2D.Double(
                        routeX, routeY,
                        x2, routeY
                ));

                g.draw(new Line2D.Double(
                        x2, routeY,
                        x2, y2
                ));

                drawArrowHead(
                        g,
                        x2,
                        routeY,
                        x2,
                        y2
                );

            } else if (page == 2 &&
                    ((left.name.equals("INSTRUCTOR_EVALUATIONS") && right.name.equals("EVALUATION_ANSWERS")) ||
                     (left.name.equals("EVALUATION_ANSWERS") && right.name.equals("INSTRUCTOR_EVALUATIONS")))) {

                // FINAL IE TO EA SEMICIRCLE
                Box ieBox = boxes.get("INSTRUCTOR_EVALUATIONS");
                Box eaBox = boxes.get("EVALUATION_ANSWERS");

                int arcStartX = ieBox.x + ieBox.width;
                int arcStartY = ieBox.centerY();

                int arcEndX = eaBox.x;
                int arcEndY = eaBox.centerY();

                int arcMiddleX =
                        (arcStartX + arcEndX) / 2;

                int arcTopY = 580;

                java.awt.geom.QuadCurve2D.Double evaluationArc =
                        new java.awt.geom.QuadCurve2D.Double(
                                arcStartX,
                                arcStartY,
                                arcMiddleX,
                                arcTopY,
                                arcEndX,
                                arcEndY
                        );

                g.draw(evaluationArc);

                drawArrowHead(
                        g,
                        arcMiddleX,
                        arcTopY,
                        arcEndX,
                        arcEndY
                );

                String arcText = "1 : M contains";

                FontMetrics arcFm =
                        g.getFontMetrics();

                // Put "contains" directly ON the arc,
                // slightly to the right of center.
                double arcLabelT = 0.58;
                double arcLabelOneMinusT = 1.0 - arcLabelT;

                int arcLabelPointX =
                        (int)Math.round(
                                arcLabelOneMinusT * arcLabelOneMinusT * arcStartX +
                                2 * arcLabelOneMinusT * arcLabelT * arcMiddleX +
                                arcLabelT * arcLabelT * arcEndX
                        );

                int arcLabelPointY =
                        (int)Math.round(
                                arcLabelOneMinusT * arcLabelOneMinusT * arcStartY +
                                2 * arcLabelOneMinusT * arcLabelT * arcTopY +
                                arcLabelT * arcLabelT * arcEndY
                        );

                int arcTextX =
                        arcLabelPointX -
                        arcFm.stringWidth(arcText) / 2;

                int arcTextY =
                        arcLabelPointY + 10;

                g.setColor(
                        new Color(
                                255,
                                255,
                                255,
                                235
                        )
                );

                g.fillRect(
                        arcTextX - 8,
                        arcTextY - arcFm.getAscent(),
                        arcFm.stringWidth(arcText) + 16,
                        arcFm.getHeight()
                );

                g.setColor(
                        new Color(
                                70,
                                70,
                                70
                        )
                );

                g.drawString(
                        arcText,
                        arcTextX,
                        arcTextY
                );

                continue;

            } else if (page == 3 &&
                    ((left.name.equals("STUDENT_INVOICES") && right.name.equals("FINANCIAL_TRANSACTIONS")) ||
                     (left.name.equals("FINANCIAL_TRANSACTIONS") && right.name.equals("STUDENT_INVOICES")))) {

                // PAGE 3 INVOICE TO FINANCIAL TOP ROUTE
                Box invoiceRouteBox =
                        boxes.get("STUDENT_INVOICES");

                Box financialRouteBox =
                        boxes.get("FINANCIAL_TRANSACTIONS");

                int startXRoute =
                        invoiceRouteBox.centerX();

                int startYRoute =
                        invoiceRouteBox.y;

                int endXRoute =
                        financialRouteBox.centerX();

                int endYRoute =
                        financialRouteBox.y;

                // Horizontal route ABOVE PAYMENTS
                int topRouteY = Math.min(startYRoute, endYRoute) - 140;

                g.draw(new Line2D.Double(
                        startXRoute,
                        startYRoute,
                        startXRoute,
                        topRouteY
                ));

                g.draw(new Line2D.Double(
                        startXRoute,
                        topRouteY,
                        endXRoute,
                        topRouteY
                ));

                g.draw(new Line2D.Double(
                        endXRoute,
                        topRouteY,
                        endXRoute,
                        endYRoute
                ));

                drawArrowHead(
                        g,
                        endXRoute,
                        topRouteY,
                        endXRoute,
                        endYRoute
                );

                // Correct relationship from the Mermaid source
                String invoiceFinancialText =
                        "0..1 : M generates";

                FontMetrics invoiceFinancialFm =
                        g.getFontMetrics();

                int invoiceFinancialTextX =
                        ((startXRoute + endXRoute) / 2) -
                        (invoiceFinancialFm.stringWidth(
                                invoiceFinancialText
                        ) / 2);

                // Text underneath the upper line,
                // still above PAYMENTS.
                int invoiceFinancialTextY =
                        topRouteY + 38;

                g.setColor(
                        new Color(
                                255,
                                255,
                                255,
                                235
                        )
                );

                g.fillRect(
                        invoiceFinancialTextX - 8,
                        invoiceFinancialTextY -
                                invoiceFinancialFm.getAscent(),
                        invoiceFinancialFm.stringWidth(
                                invoiceFinancialText
                        ) + 16,
                        invoiceFinancialFm.getHeight()
                );

                g.setColor(
                        new Color(
                                70,
                                70,
                                70
                        )
                );

                g.drawString(
                        invoiceFinancialText,
                        invoiceFinancialTextX,
                        invoiceFinancialTextY
                );

                continue;

            } else if (page == 2 &&
                    ((left.name.equals("SEMESTERS") && right.name.equals("CALENDAR_EVENTS")) ||
                     (left.name.equals("CALENDAR_EVENTS") && right.name.equals("SEMESTERS")))) {

                // PAGE 2 SEMESTERS TO CALENDAR SIDE ROUTE
                Box semesterEventBox =
                        boxes.get("SEMESTERS");

                Box calendarEventBox =
                        boxes.get("CALENDAR_EVENTS");

                // Start from the RIGHT side of SEMESTERS
                int semesterStartX =
                        semesterEventBox.x +
                        semesterEventBox.width;

                int semesterStartY =
                        semesterEventBox.centerY();

                // Enter CALENDAR_EVENTS from its RIGHT side
                int calendarEndX =
                        calendarEventBox.x +
                        calendarEventBox.width;

                int calendarEndY =
                        calendarEventBox.centerY();

                // Side lane near the right edge of Page 2
                int eventSideX = 2660;

                // SEMESTERS -> right side
                g.draw(new Line2D.Double(
                        semesterStartX,
                        semesterStartY,
                        eventSideX,
                        semesterStartY
                ));

                // Rise vertically beside EVALUATION_ANSWERS
                g.draw(new Line2D.Double(
                        eventSideX,
                        semesterStartY,
                        eventSideX,
                        calendarEndY
                ));

                // Go left into CALENDAR_EVENTS
                g.draw(new Line2D.Double(
                        eventSideX,
                        calendarEndY,
                        calendarEndX,
                        calendarEndY
                ));

                drawArrowHead(
                        g,
                        eventSideX,
                        calendarEndY,
                        calendarEndX,
                        calendarEndY
                );

                String eventText =
                        "1 : M has_event";

                FontMetrics eventFm =
                        g.getFontMetrics();

                // Put the label beside the vertical routed line
                int eventTextX =
                        eventSideX -
                        eventFm.stringWidth(eventText) -
                        3;

                int eventTextY =
                        (semesterStartY +
                         calendarEndY) / 2 + 45;

                g.setColor(
                        new Color(
                                255,
                                255,
                                255,
                                235
                        )
                );

                g.fillRect(
                        eventTextX - 7,
                        eventTextY - eventFm.getAscent(),
                        eventFm.stringWidth(eventText) + 14,
                        eventFm.getHeight()
                );

                g.setColor(
                        new Color(
                                70,
                                70,
                                70
                        )
                );

                g.drawString(
                        eventText,
                        eventTextX,
                        eventTextY
                );

                continue;

            } else if (page == 1 &&
                    left.name.equals("COURSES") &&
                    right.name.equals("COURSE_PREREQUISITES") &&
                    relation.label.equals("has_prerequisites")) {

                // PAGE 1 TWO COURSE PREREQUISITE ROUTES
                // First line: has_prerequisites

                Box coursePrereqA = boxes.get("COURSES");
                Box prereqA = boxes.get("COURSE_PREREQUISITES");

                int startXA =
                        coursePrereqA.x +
                        coursePrereqA.width - 165;

                int startYA =
                        coursePrereqA.y +
                        coursePrereqA.height;

                int laneXA = 1680;

                int endXA =
                        prereqA.x + 120;

                int endYA =
                        prereqA.y;

                int bendTopYA = 760;
                int bendBottomYA = 1390;

                g.draw(new Line2D.Double(
                        startXA,
                        startYA,
                        laneXA,
                        bendTopYA
                ));

                g.draw(new Line2D.Double(
                        laneXA,
                        bendTopYA,
                        laneXA,
                        bendBottomYA
                ));

                g.draw(new Line2D.Double(
                        laneXA,
                        bendBottomYA,
                        endXA,
                        endYA
                ));

                drawArrowHead(
                        g,
                        laneXA,
                        bendBottomYA,
                        endXA,
                        endYA
                );

                String prereqTextA =
                        "1 : M has_prerequisites";

                FontMetrics prereqFmA =
                        g.getFontMetrics();

                int prereqTextXA =
                        laneXA -
                        prereqFmA.stringWidth(prereqTextA) - 18;

                int prereqTextYA = 1160;

                g.setColor(new Color(255,255,255,235));

                g.fillRect(
                        prereqTextXA - 7,
                        prereqTextYA - prereqFmA.getAscent(),
                        prereqFmA.stringWidth(prereqTextA) + 14,
                        prereqFmA.getHeight()
                );

                g.setColor(new Color(70,70,70));

                g.drawString(
                        prereqTextA,
                        prereqTextXA,
                        prereqTextYA
                );

                continue;

            } else if (page == 1 &&
                    left.name.equals("COURSES") &&
                    right.name.equals("COURSE_PREREQUISITES") &&
                    relation.label.equals("is_prerequisite_for")) {

                // Second line: is_prerequisite_for

                Box coursePrereqB = boxes.get("COURSES");
                Box prereqB = boxes.get("COURSE_PREREQUISITES");

                int startXB =
                        coursePrereqB.x +
                        coursePrereqB.width - 70;

                int startYB =
                        coursePrereqB.y +
                        coursePrereqB.height;

                int laneXB = 1760;

                int endXB =
                        prereqB.x +
                        prereqB.width - 95;

                int endYB =
                        prereqB.y;

                int bendTopYB = 760;
                int bendBottomYB = 1390;

                g.draw(new Line2D.Double(
                        startXB,
                        startYB,
                        laneXB,
                        bendTopYB
                ));

                g.draw(new Line2D.Double(
                        laneXB,
                        bendTopYB,
                        laneXB,
                        bendBottomYB
                ));

                g.draw(new Line2D.Double(
                        laneXB,
                        bendBottomYB,
                        endXB,
                        endYB
                ));

                drawArrowHead(
                        g,
                        laneXB,
                        bendBottomYB,
                        endXB,
                        endYB
                );

                String prereqTextB =
                        "1 : M is_prerequisite_for";

                FontMetrics prereqFmB =
                        g.getFontMetrics();

                int prereqTextXB =
                        laneXB + 14;

                int prereqTextYB = 1270;

                g.setColor(new Color(255,255,255,235));

                g.fillRect(
                        prereqTextXB - 7,
                        prereqTextYB - prereqFmB.getAscent(),
                        prereqFmB.stringWidth(prereqTextB) + 14,
                        prereqFmB.getHeight()
                );

                g.setColor(new Color(70,70,70));

                g.drawString(
                        prereqTextB,
                        prereqTextXB,
                        prereqTextYB
                );

                continue;

            } else if (page == 1 &&
                    left.name.equals("COURSES") &&
                    right.name.equals("COURSE_PREREQUISITES") &&
                    relation.label.equals("has_prerequisites")) {

                // FIRST STRAIGHT LINE
                Box courseA =
                        boxes.get("COURSES");

                Box prerequisiteA =
                        boxes.get("COURSE_PREREQUISITES");

                int lineAX =
                        courseA.x + 135;

                int lineAStartY =
                        courseA.y + courseA.height;

                int lineAEndY =
                        prerequisiteA.y;

                g.draw(
                        new Line2D.Double(
                                lineAX,
                                lineAStartY,
                                lineAX,
                                lineAEndY
                        )
                );

                drawArrowHead(
                        g,
                        lineAX,
                        lineAStartY,
                        lineAX,
                        lineAEndY
                );

                String textA =
                        "1 : M has_prerequisites";

                FontMetrics fmA =
                        g.getFontMetrics();

                int textAX =
                        lineAX -
                        fmA.stringWidth(textA) -
                        12;

                int textAY =
                        lineAEndY - 210;

                g.setColor(
                        new Color(
                                255,
                                255,
                                255,
                                235
                        )
                );

                g.fillRect(
                        textAX - 7,
                        textAY - fmA.getAscent(),
                        fmA.stringWidth(textA) + 14,
                        fmA.getHeight()
                );

                g.setColor(
                        new Color(
                                70,
                                70,
                                70
                        )
                );

                g.drawString(
                        textA,
                        textAX,
                        textAY
                );

                continue;

            } else if (page == 1 &&
                    left.name.equals("COURSES") &&
                    right.name.equals("COURSE_PREREQUISITES") &&
                    relation.label.equals("is_prerequisite_for")) {

                // SECOND STRAIGHT LINE
                Box courseB =
                        boxes.get("COURSES");

                Box prerequisiteB =
                        boxes.get("COURSE_PREREQUISITES");

                int lineBX =
                        courseB.x + 295;

                int lineBStartY =
                        courseB.y + courseB.height;

                int lineBEndY =
                        prerequisiteB.y;

                g.draw(
                        new Line2D.Double(
                                lineBX,
                                lineBStartY,
                                lineBX,
                                lineBEndY
                        )
                );

                drawArrowHead(
                        g,
                        lineBX,
                        lineBStartY,
                        lineBX,
                        lineBEndY
                );

                String textB =
                        "1 : M is_prerequisite_for";

                FontMetrics fmB =
                        g.getFontMetrics();

                int textBX =
                        lineBX + 12;

                int textBY =
                        lineBEndY - 125;

                g.setColor(
                        new Color(
                                255,
                                255,
                                255,
                                235
                        )
                );

                g.fillRect(
                        textBX - 7,
                        textBY - fmB.getAscent(),
                        fmB.stringWidth(textB) + 14,
                        fmB.getHeight()
                );

                g.setColor(
                        new Color(
                                70,
                                70,
                                70
                        )
                );

                g.drawString(
                        textB,
                        textBX,
                        textBY
                );

                continue;

            } else {

                g.draw(
                        new Line2D.Double(
                                x1,
                                y1,
                                x2,
                                y2
                        )
                );

                drawArrowHead(
                        g,
                        x1,
                        y1,
                        x2,
                        y2
                );
            }

            int labelX =
                    (x1 + x2) / 2;

            int labelY =
                    (y1 + y2) / 2;
            // PAGE 2: HAS_EXAM directly on the SECTIONS -> EXAMS line
            if (page == 2 && relation.label.equals("has_exam")) {

                Box sectionsHasExam = boxes.get("SECTIONS");
                Box examsHasExam = boxes.get("EXAMS");

                int midX =
                        (sectionsHasExam.centerX() +
                         examsHasExam.centerX()) / 2;

                int midY =
                        (sectionsHasExam.centerY() +
                         examsHasExam.centerY()) / 2;

                double dx =
                        sectionsHasExam.centerX() -
                        examsHasExam.centerX();

                double dy =
                        sectionsHasExam.centerY() -
                        examsHasExam.centerY();

                double len =
                        Math.sqrt(dx * dx + dy * dy);

                // Move about 3 cm DOWN along the SAME line
                int moveDown = 165;

                labelX =
                        midX +
                        (int)Math.round((dx / len) * moveDown);

                labelY =
                        midY +
                        (int)Math.round((dy / len) * moveDown);
            }
// Page 1: put the INSTRUCTORS -> SECTIONS label
            // on the long lower routed segment.
            if (page == 1 &&
                    ((left.name.equals("INSTRUCTORS") && right.name.equals("SECTIONS")) ||
                     (left.name.equals("SECTIONS") && right.name.equals("INSTRUCTORS")))) {

                labelX = 1440;
                labelY = 1705;
            }

            // Page 1: move COURSES <-> COURSE_PREREQUISITES label lower
            if (page == 1 &&
                    ((left.name.equals("COURSES") && right.name.equals("COURSE_PREREQUISITES")) ||
                     (left.name.equals("COURSE_PREREQUISITES") && right.name.equals("COURSES")))) {

                labelY += 110;
            }

            // Page 1: separate the three crowded labels
            if (page == 1) {
                if (left.name.equals("USERS") && right.name.equals("INSTRUCTORS")) {
                    labelX -= 70;
                    labelY += 15;
                } else if (left.name.equals("USERS") && right.name.equals("STUDENTS")) {
                    labelX += 70;
                    labelY += 30;
                } else if (left.name.equals("DEPARTMENTS") && right.name.equals("INSTRUCTORS")) {
                    labelX += 25;
                    labelY -= 55;
                }
            }// Page 3: keep relationship text clear of the line
            if (page == 3) {

                double lineDx = x2 - x1;
                double lineDy = y2 - y1;

                double lineLength =
                        Math.sqrt(
                                lineDx * lineDx +
                                lineDy * lineDy
                        );

                if (lineLength > 0) {
                    labelX +=
                            (int) Math.round(
                                    (-lineDy / lineLength) * 55
                            );

                    labelY +=
                            (int) Math.round(
                                    (lineDx / lineLength) * 55
                            );
                }

                switch (relation.label) {

                    case "priced_by" -> labelY -= 20;

                    case "due_in" -> {
                        labelX += 20;
                        labelY -= 20;
                    }

                    case "billed_for" -> {
                        labelX = (x1 + x2) / 2;
                        labelY = 655;
                    }

                    case "owes" -> labelX -= 25;

                    case "billed" -> {
                        labelX = 1360;
                        labelY = 565;
                    }

                    case "pays" -> {
                        labelX -= 80;
                        labelY += 15;
                    }

                    case "has_ledger_entry" -> {
                        labelX = (x1 + x2) / 2;
                        labelY = (y1 + y2) / 2;
                        labelY += 120;
                    }

                    case "categorizes" -> {
                        labelX += 15;
                        labelY -= 15;
                    }

                    case "line_items" -> {
                        labelX -= 20;
                        labelY -= 20;
                    }

                    case "paid_by" -> {
                        labelX += 15;
                        labelY += 15;
                    }

                    case "generates" -> labelY -= 15;

                    case "receives" -> {
                        labelX = x1 + (int)((x2 - x1) * 0.48);
                        labelY = y1 + (int)((y2 - y1) * 0.48);
                        labelX += 30;
                        labelY -= 55;
                    }

                    case "performs" -> {
                        labelX = x1 + (int)((x2 - x1) * 0.48);
                        labelY = y1 + (int)((y2 - y1) * 0.48);
                        labelX += 65;
                    }

                    case "records" -> {
                        labelX = x1 + (int)((x2 - x1) * 0.72);
                        labelY = y1 + (int)((y2 - y1) * 0.72);
                        labelX -= 45;
                        labelY -= 165;
                    }

                    case "creates" -> {
                        labelX = x1 + (int)((x2 - x1) * 0.52);
                        labelY = y1 + (int)((y2 - y1) * 0.52);
                        labelX += 70;
                        labelY -= 210;
                    }
                }
            }

            // Page 2: separate crowded relationship labels
            if (page == 2) {
                switch (relation.label) {
                    case "tracked_by" -> {
                        labelX -= 70;
                        labelY -= 45;
                    }
                    case "produces" -> {
                        labelY -= 12;
                    }
                    case "may_submit" -> {
                        labelY -= 12;
                    }
                    case "fills" -> {
                        labelY -= 12;
                    }
                    case "has_exam" -> {
                        labelX -= 30;
                        labelY -= 60;
                    }
                    case "has_attendance" -> {
                        labelX -= 20;
                        labelY += 15;
                    }
                    case "submits" -> {
                        labelX += 75;
                        labelY += 55;
                    }
                    case "records" -> {
                        labelX -= 70;
                        labelY -= 50;
                    }
                    case "answered_by" -> {
                        labelX -= 45;
                        labelY -= 55;
                    }
                    case "creates" -> {
                        labelX += 60;
                        labelY += 55;
                    }
                    case "contains" -> {
                        labelX += 60;
                        labelY -= 55;
                    }
                    case "has_event" -> {
                        labelX += 55;
                        labelY += 55;
                    }
                }
            }

            String op = relation.operator;
            String cardinality;

            if (op.equals("||--o{") || op.equals("||--|{")) {
                cardinality = "1 : M";
            } else if (op.equals("o{--||") || op.equals("|{--||")) {
                cardinality = "M : 1";
            } else if (op.equals("||--o|") || op.equals("o|--||") ||
                       op.equals("||--||")) {
                cardinality = "1 : 1";
            } else if (op.equals("o|--o{") || op.equals("o{--o|")) {
                cardinality = "0..1 : M";
            } else {
                cardinality = "";
            }

            String label = cardinality.isEmpty()
                    ? relation.label
                    : cardinality + "  " + relation.label;

            // Page 2: keep the same USERS -> GRADES label position,
            // only change its displayed text.
            if (page == 2 && relation.label.equals("submits")) {
                label = cardinality.isEmpty()
                        ? "submitted_and_modified"
                        : cardinality + "  submitted_and_modified";
            }

            // Page 3 top-row finance relationships:
            // use smaller text so every label fits between the tables.
            if (page == 3 &&
                    (relation.label.equals("categorizes") ||
                     relation.label.equals("line_items") ||
                     relation.label.equals("paid_by") ||
                     relation.label.equals("generates"))) {

                g.setFont(new Font("Arial", Font.BOLD, 14));

            } else if (page == 3) {

                g.setFont(new Font("Arial", Font.BOLD, 20));
            }

            if (page == 1) {
                if (relation.label.equals("has_profile") || relation.label.equals("employs")) {
                    g.setFont(new Font("Arial", Font.BOLD, 20));
                } else {
                    g.setFont(RELATION_FONT);
                }
            }
FontMetrics fm =
                    g.getFontMetrics();

            int w =
                    fm.stringWidth(label);

            g.setColor(
                    new Color(
                            255,
                            255,
                            255,
                            220
                    )
            );

            g.fillRect(
                    labelX - w / 2 - 8,
                    labelY - 28,
                    w + 16,
                    38
            );

            g.setColor(
                    new Color(
                            70,
                            70,
                            70
                    )
            );

            g.drawString(
                    label,
                    labelX - w / 2,
                    labelY
            );
        }
    }

    private static void drawArrowHead(
            Graphics2D g,
            int x1,
            int y1,
            int x2,
            int y2
    ) {

        double angle =
                Math.atan2(
                        y2 - y1,
                        x2 - x1
                );

        int length = 14;

        double a1 =
                angle + Math.PI * 0.82;

        double a2 =
                angle - Math.PI * 0.82;

        int ax1 =
                x2 +
                        (int) (
                                Math.cos(a1)
                                        * length
                        );

        int ay1 =
                y2 +
                        (int) (
                                Math.sin(a1)
                                        * length
                        );

        int ax2 =
                x2 +
                        (int) (
                                Math.cos(a2)
                                        * length
                        );

        int ay2 =
                y2 +
                        (int) (
                                Math.sin(a2)
                                        * length
                        );

        g.drawLine(
                x2,
                y2,
                ax1,
                ay1
        );

        g.drawLine(
                x2,
                y2,
                ax2,
                ay2
        );
    }

    private static void drawCrossPageRelations(
            Graphics2D g,
            int page,
            List<Relation> relations,
            Map<String, Entity> entities
    ) {

        List<Relation> cross =
                new ArrayList<>();

        Set<String> drawnPage3Pairs = new HashSet<>();

        for (Relation relation : relations) {

            Entity left =
                    entities.get(relation.left);

            Entity right =
                    entities.get(relation.right);

            if (left == null || right == null) {
                continue;
            }

            if (left.page != right.page
                    && right.page == page) {
                cross.add(relation);
            }
        }

        int panelY =
                HEIGHT - BOTTOM_RESERVED + 35;

        g.setColor(
                new Color(245, 247, 250)
        );

        g.fillRoundRect(
                MARGIN,
                panelY,
                WIDTH - (2 * MARGIN),
                BOTTOM_RESERVED - 110,
                16,
                16
        );

        g.setColor(
                new Color(31, 56, 100)
        );

        g.setFont(
                new Font(
                        "Arial",
                        Font.BOLD,
                        21
                )
        );

        g.drawString(
                "Cross-page relationships",
                MARGIN + 18,
                panelY + 31
        );

        g.setFont(
                new Font(
                        "Consolas",
                        Font.PLAIN,
                        15
                )
        );

        if (cross.isEmpty()) {

            g.setColor(
                    new Color(80, 80, 80)
            );

            g.drawString(
                    "No incoming cross-page relationships on this page.",
                    MARGIN + 18,
                    panelY + 62
            );

            return;
        }

        int columns = 2;

        int columnWidth =
                (WIDTH - (2 * MARGIN) - 40)
                        / columns;

        int row = 0;
        int col = 0;

        for (Relation relation : cross) {

            Entity left =
                    entities.get(relation.left);

            int x =
                    MARGIN + 18 +
                    col * columnWidth;

            int y =
                    panelY + 62 +
                    row * 23;

            String text =
                    relation.display() +
                    "  [from page " +
                    left.page +
                    "]";

            g.setColor(
                    new Color(60, 60, 60)
            );

            g.drawString(
                    text,
                    x,
                    y
            );

            row++;

            if (row >= 9) {
                row = 0;
                col++;

                if (col >= columns) {
                    break;
                }
            }
        }
    }

    private static void drawLegend(
            Graphics2D g
    ) {

        int y = HEIGHT - 36;

        g.setFont(
                new Font(
                        "Arial",
                        Font.PLAIN,
                        15
                )
        );

        g.setColor(
                new Color(70, 70, 70)
        );

        g.drawString(
                "Legend: PK = Primary Key   |   FK = Foreign Key   |   UK = Unique Key   |   Mermaid cardinalities are preserved on relationship labels.",
                MARGIN,
                y
        );

        g.drawString(
                "Source: corrected_er_diagram.mmd",
                WIDTH - 460,
                y
        );
    }

    private static void createPdf(
            List<Path> images,
            Path output
    ) throws Exception {

        Document document =
                new Document(
                        PageSize.A3.rotate(),
                        0,
                        0,
                        0,
                        0
                );

        PdfWriter.getInstance(
                document,
                new FileOutputStream(
                        output.toFile()
                )
        );

        document.open();

        for (int i = 0; i < images.size(); i++) {

            Image image =
                    Image.getInstance(
                            images.get(i)
                                    .toAbsolutePath()
                                    .toString()
                    );

            float pageWidth =
                    document.getPageSize()
                            .getWidth();

            float pageHeight =
                    document.getPageSize()
                            .getHeight();

            image.scaleToFit(
                    pageWidth,
                    pageHeight
            );

            image.setAbsolutePosition(
                    (pageWidth -
                            image.getScaledWidth())
                            / 2,
                    (pageHeight -
                            image.getScaledHeight())
                            / 2
            );

            document.add(image);

            if (i < images.size() - 1) {
                document.newPage();
            }
        }

        document.close();
    }
}




