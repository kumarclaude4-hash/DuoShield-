package com.duoshield.app.util;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import androidx.core.content.FileProvider;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Message;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

public class ExportHelper {

    public static void exportToPdf(Context ctx, String convId) {
        new Thread(() -> {
            List<Message> msgs = AppDatabase.getInstance(ctx).messageDao().getMessages(convId);
            PdfDocument doc = new PdfDocument();
            File outFile = null;
            try {
                Paint paint = new Paint();
                paint.setTextSize(11);
                int y = 40;
                int pageNum = 1;
                PdfDocument.PageInfo pi = new PdfDocument.PageInfo.Builder(595, 842, pageNum).create();
                PdfDocument.Page page = doc.startPage(pi);
                Canvas canvas = page.getCanvas();
                paint.setFakeBoldText(true);
                canvas.drawText("DuoShield Chat Export", 40, y, paint);
                y += 20;
                canvas.drawText("Conversation: " + convId.substring(0, Math.min(20, convId.length())), 40, y, paint);
                y += 30;
                paint.setFakeBoldText(false);

                for (Message m : msgs) {
                    if (y > 800) {
                        doc.finishPage(page);
                        pageNum++;
                        pi = new PdfDocument.PageInfo.Builder(595, 842, pageNum).create();
                        page = doc.startPage(pi);
                        canvas = page.getCanvas();
                        y = 40;
                    }
                    String sender = m.getSender() != null
                        ? m.getSender().substring(0, Math.min(8, m.getSender().length())) : "?";
                    String line = TimeFormatter.formatFull(m.getTimestamp())
                        + "  [" + sender + "]  "
                        + (m.getText() != null ? m.getText() : "[media]")
                        + (m.isEdited() ? " (edited)" : "");
                    if (line.length() > 95) line = line.substring(0, 92) + "...";
                    canvas.drawText(line, 40, y, paint);
                    y += 18;
                }
                doc.finishPage(page);

                outFile = new File(ctx.getCacheDir(),
                    "duoshield_export_" + System.currentTimeMillis() + ".pdf");
                // try-with-resources ensures stream is always closed even if writeTo throws
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    doc.writeTo(fos);
                }
            } catch (Exception ignored) {
                return;
            } finally {
                doc.close();
            }

            try {
                Uri uri = FileProvider.getUriForFile(ctx,
                    ctx.getPackageName() + ".provider", outFile);
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("application/pdf");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(Intent.createChooser(intent, "Export Chat")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {}
        }).start();
    }
}
