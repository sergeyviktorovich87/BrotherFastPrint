package ru.brotherfastprint.render;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import ru.brotherfastprint.render.IRenderEvents;
interface IRenderService {
    Bundle prepare(in ParcelFileDescriptor pdf, in ParcelFileDescriptor mono,
                 in ParcelFileDescriptor hbp, in Bundle options, IRenderEvents events);
    void cancel();
}
