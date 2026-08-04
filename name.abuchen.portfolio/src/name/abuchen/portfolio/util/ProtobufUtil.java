package name.abuchen.portfolio.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;

import name.abuchen.portfolio.model.proto.v1.PDecimalValue;
import name.abuchen.portfolio.model.proto.v1.PLocalDateTime;

public class ProtobufUtil
{
    private ProtobufUtil()
    {
    }

    public static Timestamp asTimestamp(LocalDateTime localDateTime)
    {
        Instant instant = localDateTime.toInstant(ZoneOffset.UTC);

        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    public static LocalDateTime fromTimestamp(Timestamp ts)
    {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos()), ZoneOffset.UTC);
    }

    /**
     * {@code updatedAt} is the one timestamp in the model that can legitimately
     * be unset: a security, account, portfolio or transaction built in memory has
     * none until something changes it, and files written before the migration
     * that assigned them have none either. Dereferencing it made saving such a
     * model fail with a {@code NullPointerException} wrapped in an
     * {@code IOException} - a message naming neither the field nor the file.
     * <p>
     * The empty timestamp is what "unset" is written as, and
     * {@link #fromUpdatedAtTimestamp} reads it back as {@code null}, so the round
     * trip preserves it. That reserves the Unix epoch itself as a value: an
     * instant of exactly {@code 1970-01-01T00:00:00Z} reads back as unset, which
     * is the correct reading of it - nothing in this application was modified
     * before it existed.
     */
    public static Timestamp asUpdatedAtTimestamp(Instant instant)
    {
        if (instant == null)
            return Timestamp.getDefaultInstance();

        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    public static Instant fromUpdatedAtTimestamp(Timestamp ts)
    {
        if (ts == null || ts.equals(Timestamp.getDefaultInstance()))
            return null;

        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
    }

    public static PDecimalValue asDecimalValue(BigDecimal number)
    {
        return PDecimalValue.newBuilder().setScale(number.scale()).setPrecision(number.precision())
                        .setValue(ByteString.copyFrom(number.unscaledValue().toByteArray())).build();
    }

    public static BigDecimal fromDecimalValue(PDecimalValue number)
    {
        MathContext mc = new MathContext(number.getPrecision());
        return new BigDecimal(new BigInteger(number.getValue().toByteArray()), number.getScale(), mc);
    }

    public static PLocalDateTime asLocalDateTime(LocalDateTime dateTime)
    {
        return PLocalDateTime.newBuilder().setEpochDay(dateTime.toLocalDate().toEpochDay())
                        .setSecondOfDay(dateTime.toLocalTime().toSecondOfDay()).build();
    }

    public static LocalDateTime fromLocalDateTime(PLocalDateTime dateTime)
    {
        return LocalDateTime.of(LocalDate.ofEpochDay(dateTime.getEpochDay()),
                        LocalTime.ofSecondOfDay(dateTime.getSecondOfDay()));
    }
}
