package com.university.util;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.university.service.TranscriptService.TermBlock;
import com.university.service.TranscriptService.Transcript;
import com.university.service.TranscriptService.TranscriptRow;

import java.awt.Color;                       // OpenPDF uses java.awt.Color, NOT BaseColor
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.format.DateTimeFormatter;

/**
 * Renders a {@link Transcript} as an official-looking A4 PDF.
 *
 * <p>This class is PURE OUTPUT:</p>
 * <ul>
 *   <li>no JDBC, no SQL, no {@code DBConnection};</li>
 *   <li>no JavaFX dialogs;</li>
 *   <li>no GPA arithmetic — every number was already computed by
 *       {@code TranscriptService}, so the paper and the screen can never disagree.</li>
 * </ul>
 *
 * <p>Library: OpenPDF 1.3.30, the LGPL/MPL fork of iText 2.1.7 — hence the
 * {@code com.lowagie.text} package. See phase-12/context/PDF_LAYOUT.md.</p>
 */
public final class PdfExporter {

    private PdfExporter() {
    }

    /* ---------------- university identity ------------------------------------ */
    private static final String UNIVERSITY_NAME = "ISLAMIC UNIVERSITY OF LEBANON";
    private static final String DOC_TITLE       = "OFFICIAL ACADEMIC TRANSCRIPT";

    /* ---------------- colours ------------------------------------------------- */
    private static final Color HEADER_BG = new Color(0x1F, 0x39, 0x5C);   // deep navy
    private static final Color HEADER_FG = Color.WHITE;
    private static final Color STRIPE_BG = new Color(0xF2, 0xF5, 0xF8);
    private static final Color BORDER    = new Color(0xBB, 0xC4, 0xCE);
    private static final Color GREY_TEXT = new Color(0x88, 0x88, 0x88);
    private static final Color BOX_BG    = new Color(0xF7, 0xF9, 0xFB);

    /* ---------------- fonts (resolved once, lazily) --------------------------- */
    private static BaseFont base;
    private static Font fUniversity;
    private static Font fDocTitle;
    private static Font fLabel;
    private static Font fValue;
    private static Font fTerm;
    private static Font fTableHead;
    private static Font fTableBody;
    private static Font fTableBodyGrey;
    private static Font fTotal;
    private static Font fFooter;

    /* ---------------- table geometry ------------------------------------------ */
    /** Code, Course title, Cr, Mark, Grade, Pts, Note — RELATIVE widths. */
    private static final float[] COLUMN_WIDTHS = { 12f, 40f, 7f, 10f, 10f, 8f, 13f };
    private static final String[] COLUMN_TITLES =
            { "Code", "Course title", "Cr", "Mark", "Grade", "Pts", "Note" };
    private static final int[] COLUMN_ALIGN = {
            Element.ALIGN_LEFT, Element.ALIGN_LEFT, Element.ALIGN_CENTER, Element.ALIGN_RIGHT,
            Element.ALIGN_CENTER, Element.ALIGN_RIGHT, Element.ALIGN_LEFT };

    private static final DateTimeFormatter D  = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /* =====================================================================
       PUBLIC METHODS
       ===================================================================== */

    /**
     * Writes a single tuition installment as a small, official-looking
     * voucher — everything a cashier needs to identify the payment, nothing
     * more. Every value is a plain string already formatted by the caller,
     * so this class stays pure output and does not need to know about
     * {@code TuitionInstallment} or currency formatting rules.
     *
     * @throws IOException when the file cannot be written, most often
     *                      because it is still open in a PDF viewer
     */
    public static void exportInstallmentVoucher(String studentId, String studentName, String semesterName,
                                                  String bankReference, String currency, String amount,
                                                  String dueDate, String status, File target)
            throws IOException {
        if (target == null) {
            throw new IllegalArgumentException("Target file is null.");
        }

        initFonts();

        Document doc = new Document(PageSize.A5, 40f, 40f, 40f, 40f);
        try (OutputStream out = new FileOutputStream(target)) {
            PdfWriter.getInstance(doc, out);
            doc.addTitle("Payment Voucher");
            doc.addAuthor(UNIVERSITY_NAME);
            doc.open();

            Paragraph university = new Paragraph(UNIVERSITY_NAME, fUniversity);
            university.setAlignment(Element.ALIGN_CENTER);
            doc.add(university);

            Paragraph title = new Paragraph("PAYMENT VOUCHER", fDocTitle);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(14f);
            doc.add(title);

            PdfPTable box = new PdfPTable(new float[]{ 35f, 65f });
            box.setWidthPercentage(100f);
            addField(box, "Student ID", studentId);
            addField(box, "Student Name", studentName);
            addField(box, "Semester", semesterName);
            addField(box, "Bank Reference", bankReference);
            addField(box, "Currency", currency);
            addField(box, "Amount", amount);
            addField(box, "Due Date", dueDate);
            addField(box, "Status", status);
            doc.add(box);

            Paragraph note = new Paragraph(
                "This voucher is computer generated by the University Registration System.", fFooter);
            note.setSpacingBefore(16f);
            doc.add(note);

            doc.close();
        } catch (DocumentException e) {
            if (target.exists() && target.length() == 0) {
                target.delete();
            }
            throw new IOException("Could not build the voucher: " + e.getMessage(), e);
        }
    }

    /**
     * Writes {@code transcript} to {@code target} as an A4 PDF.
     *
     * @throws IOException when the file cannot be written — most often because it is still
     *                     open in a PDF viewer, which locks it on Windows
     */
    public static void exportTranscript(Transcript transcript, File target) throws IOException {
        if (transcript == null) {
            throw new IllegalArgumentException("Transcript is null.");
        }
        if (target == null) {
            throw new IllegalArgumentException("Target file is null.");
        }

        initFonts();

        // (pageSize, left, right, top, bottom) — the bottom is deeper because the footer sits in it.
        Document doc = new Document(PageSize.A4, 40f, 40f, 40f, 54f);
        try (OutputStream out = new FileOutputStream(target)) {

            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new FooterEvent());   // AFTER getInstance, BEFORE open — or no footer
            doc.addTitle(DOC_TITLE);
            doc.addAuthor(UNIVERSITY_NAME);
            doc.open();

            addLetterhead(doc);
            addStudentBox(doc, transcript);

            if (transcript.isEmpty()) {
                Paragraph none = new Paragraph("No academic record yet.", fValue);
                none.setSpacingBefore(20f);
                doc.add(none);
            } else {
                for (TermBlock block : transcript.terms) {
                    addTermHeading(doc, block);
                    doc.add(buildTermTable(block));
                    addTermSummary(doc, block);
                }
                addSummaryBox(doc, transcript);
            }

            addDisclaimer(doc);
            doc.close();                              // flushes and writes the trailer

        } catch (DocumentException e) {
            // never leave a half-written file behind
            if (target.exists() && target.length() == 0) {
                target.delete();
            }
            throw new IOException("Could not build the PDF document: " + e.getMessage(), e);
        }
    }

    /* =====================================================================
       FONTS — the Arabic trap (PDF_LAYOUT.md Section 3)
       ===================================================================== */
    private static synchronized void initFonts() {
        if (base != null) {
            return;
        }
        base           = resolveUnicodeBaseFont();
        fUniversity    = new Font(base, 18f,  Font.BOLD,   HEADER_BG);
        fDocTitle      = new Font(base, 12f,  Font.BOLD,   Color.BLACK);
        fLabel         = new Font(base,  9f,  Font.BOLD,   new Color(0x55, 0x55, 0x55));
        fValue         = new Font(base,  9f,  Font.NORMAL, Color.BLACK);
        fTerm          = new Font(base, 11f,  Font.BOLD,   HEADER_BG);
        fTableHead     = new Font(base,  9f,  Font.BOLD,   HEADER_FG);
        fTableBody     = new Font(base,  9f,  Font.NORMAL, Color.BLACK);
        fTableBodyGrey = new Font(base,  9f,  Font.NORMAL, GREY_TEXT);
        fTotal         = new Font(base,  9f,  Font.BOLD,   Color.BLACK);
        fFooter        = new Font(base, 7.5f, Font.ITALIC, Color.GRAY);
    }

    /**
     * A built-in PDF font cannot draw Arabic. Load a Unicode TrueType font and EMBED it so the
     * file looks identical on any machine. Never throws: a missing font must not stop the export.
     */
    private static BaseFont resolveUnicodeBaseFont() {
        String[] candidates = {
            "C:/Windows/Fonts/arial.ttf",
            "C:/Windows/Fonts/tahoma.ttf",
            "C:/Windows/Fonts/segoeui.ttf"
        };
        for (String path : candidates) {
            try {
                if (new File(path).exists()) {
                    return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                }
            } catch (Exception ignored) {
                // try the next candidate
            }
        }
        try {
            return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            throw new IllegalStateException("No usable PDF font found.", e);
        }
    }

    /* =====================================================================
       PAGE PARTS
       ===================================================================== */
    private static void addLetterhead(Document doc) throws DocumentException {
        Paragraph university = new Paragraph(UNIVERSITY_NAME, fUniversity);
        university.setAlignment(Element.ALIGN_CENTER);
        doc.add(university);

        Paragraph title = new Paragraph(DOC_TITLE, fDocTitle);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(6f);
        doc.add(title);

        // a 2pt rule drawn as a one-cell table — the simplest reliable horizontal line
        PdfPTable rule = new PdfPTable(1);
        rule.setWidthPercentage(100f);
        PdfPCell cell = new PdfPCell();
        cell.setFixedHeight(2f);
        cell.setBackgroundColor(HEADER_BG);
        cell.setBorder(PdfPCell.NO_BORDER);
        rule.addCell(cell);
        rule.setSpacingAfter(10f);
        doc.add(rule);
    }

    private static void addStudentBox(Document doc, Transcript t) throws DocumentException {
        PdfPTable box = new PdfPTable(new float[]{ 18f, 32f, 18f, 32f });
        box.setWidthPercentage(100f);
        box.setSpacingAfter(12f);

        addField(box, "Student ID",     String.valueOf(t.studentUserId));
        addField(box, "Program",        t.programName);
        addField(box, "Name",           t.fullName());
        addField(box, "Admitted",       t.admissionDate == null ? "—" : t.admissionDate.format(D));
        addField(box, "Standing",       t.academicStanding);
        addField(box, "Issued",         t.generatedAt.format(DT));

        doc.add(box);
    }

    private static void addField(PdfPTable box, String label, String value) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, fLabel));
        labelCell.setBackgroundColor(BOX_BG);
        labelCell.setBorderColor(BORDER);
        labelCell.setPadding(5f);
        box.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value == null ? "" : value, fValue));
        valueCell.setBorderColor(BORDER);
        valueCell.setPadding(5f);
        box.addCell(valueCell);
    }

    private static void addTermHeading(Document doc, TermBlock block) throws DocumentException {
        Paragraph heading = new Paragraph(block.heading(), fTerm);
        heading.setSpacingBefore(10f);
        heading.setSpacingAfter(2f);
        doc.add(heading);
    }

    /*
     * THE TABLE. Every row goes through addBodyRow(), which always adds exactly
     * COLUMN_WIDTHS.length cells. Never call addCell() ad hoc: OpenPDF pads a short final row
     * silently, so a missing cell shifts every later value one column left and the bug looks
     * like "my marks are in the grade column".
     */
    private static PdfPTable buildTermTable(TermBlock block) throws DocumentException {
        PdfPTable table = new PdfPTable(COLUMN_WIDTHS);
        table.setWidthPercentage(100f);
        table.setHeaderRows(1);                 // repeats the header on every page it spills onto
        table.setSpacingBefore(4f);
        table.setSpacingAfter(2f);

        for (int i = 0; i < COLUMN_TITLES.length; i++) {
            PdfPCell header = new PdfPCell(new Phrase(COLUMN_TITLES[i], fTableHead));
            header.setBackgroundColor(HEADER_BG);
            header.setBorderColor(HEADER_BG);
            header.setPadding(5f);
            header.setHorizontalAlignment(COLUMN_ALIGN[i]);
            table.addCell(header);
        }

        int index = 0;
        for (TranscriptRow row : block.rows) {
            addBodyRow(table, row, index++);
        }
        return table;
    }

    private static void addBodyRow(PdfPTable table, TranscriptRow row, int rowIndex) {
        boolean grey = row.isSupersededRepeat();
        Font font    = grey ? fTableBodyGrey : fTableBody;
        Color bg     = (rowIndex % 2 == 0) ? STRIPE_BG : Color.WHITE;

        String[] values = {
            row.courseCode,
            row.courseTitle,
            row.creditsText(),
            row.markText(),
            row.letterText(),
            row.pointsText(),
            row.displayNote()
        };

        for (int i = 0; i < values.length; i++) {
            PdfPCell cell = new PdfPCell(new Phrase(values[i] == null ? "" : values[i], font));
            cell.setHorizontalAlignment(COLUMN_ALIGN[i]);
            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            cell.setBackgroundColor(bg);
            cell.setBorderColor(BORDER);
            cell.setPadding(4f);
            table.addCell(cell);
        }
    }

    private static void addTermSummary(Document doc, TermBlock block) throws DocumentException {
        Paragraph line = new Paragraph(block.summaryLine(), fTotal);
        line.setAlignment(Element.ALIGN_RIGHT);
        line.setSpacingAfter(6f);
        doc.add(line);
    }

    private static void addSummaryBox(Document doc, Transcript t) throws DocumentException {
        PdfPTable box = new PdfPTable(new float[]{ 34f, 33f, 33f });
        box.setWidthPercentage(100f);
        box.setSpacingBefore(14f);
        box.setSpacingAfter(10f);

        addSummaryCell(box, "Credits earned",    t.creditsEarned + " / " + t.creditsRequired);
        addSummaryCell(box, "Cumulative GPA",    t.cumulativeGpaText());
        addSummaryCell(box, "Academic standing", t.academicStanding);

        doc.add(box);
    }

    private static void addSummaryCell(PdfPTable box, String label, String value) {
        Paragraph paragraph = new Paragraph();
        paragraph.add(new Phrase(label + "\n", fLabel));
        paragraph.add(new Phrase(value == null ? "" : value, fTotal));

        PdfPCell cell = new PdfPCell(paragraph);
        cell.setBackgroundColor(BOX_BG);
        cell.setBorderColor(BORDER);
        cell.setPadding(8f);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        box.addCell(cell);
    }

    private static void addDisclaimer(Document doc) throws DocumentException {
        Paragraph note = new Paragraph(
            "This document is computer generated by the University Registration System and is "
          + "valid without a signature. Courses marked \"Repeated\" are superseded attempts and "
          + "are excluded from the grade point average. Grades of W (withdrawn) and I "
          + "(incomplete) carry no grade points.", fFooter);
        note.setSpacingBefore(12f);
        doc.add(note);
    }

    /* =====================================================================
       FOOTER — a page event, because the page number is unknown until render
       ===================================================================== */
    private static final class FooterEvent extends PdfPageEventHelper {
        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            ColumnText.showTextAligned(
                    writer.getDirectContent(),
                    Element.ALIGN_CENTER,
                    new Phrase("Page " + writer.getPageNumber(), fFooter),
                    (document.left() + document.right()) / 2f,
                    document.bottom() - 20f,
                    0f);
        }
    }
}
