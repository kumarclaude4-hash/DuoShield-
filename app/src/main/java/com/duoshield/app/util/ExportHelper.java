package com.duoshield.app.util;

import android.content.Context;
import android.content.Intent;
import android.graphics.pdf.PdfDocument;
import android.graphics.Paint;
import android.graphics.Canvas;
import android.net.Uri;
import androidx.core.content.FileProvider;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Message;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.Executors;

public class ExportHelper {

    public static void exportToPdf(Context ctx, String convId) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                List<Message> msgs = AppDatabase.getInstance(ctx).messageDao().getMessages(convId);
                PdfDocument doc = new PdfDocument();
                Paint paint = new Paint();
                paint.setTextSize(11);
                int y = 40; int pageNum = 1;
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
                    String line = TimeFormatter.formatFull(m.getTimestamp())
                        + "  [" + (m.getSender() != null ? m.getSender().substring(0, Math.min(8, m.getSender().length())) : "?") + "]  "
                        + (m.getText() != null ? m.getText() : "[media]")
                        + (m.isEdited() ? " (edited)" : "");
                    if (line.length() > 95) line = line.substring(0, 92) + "...";
                    canvas.drawText(line, 40, y, paint);
                    y += 18;
                }
                doc.finishPage(page);

                File outFile = new File(ctx.getCacheDir(), "duoshield_export_" + System.currentTimeMillis() + ".pdf");
                FileOutputStream fos = new FileOutputStream(outFile);
                doc.writeTo(fos);
                fos.close();
                doc.close();

                Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".provider", outFile);
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("application/pdf");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(Intent.createChooser(intent, "Export Chat").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {}
        });
    }
}
