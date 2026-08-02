package com.university.dao;

import com.university.model.InvoiceItem;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.invoice_items}.
 */
public class InvoiceItemDAO extends AbstractDAO implements GenericDAO<InvoiceItem> {

    private static final String SELECT =
            "SELECT invoice_item_id, invoice_id, fee_type_id, description, amount "
            + "FROM dbo.invoice_items";

    private static final String INSERT =
            "INSERT INTO dbo.invoice_items (invoice_id, fee_type_id, description, amount) "
            + "VALUES (?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.invoice_items SET fee_type_id = ?, description = ?, amount = ? "
            + "WHERE invoice_item_id = ?";

    private static final String DELETE =
            "DELETE FROM dbo.invoice_items WHERE invoice_item_id = ?";

    private static final RowMapper<InvoiceItem> MAPPER = InvoiceItemDAO::mapRow;

    static InvoiceItem mapRow(ResultSet rs) throws SQLException {
        InvoiceItem item = new InvoiceItem();
        item.setInvoiceItemId(rs.getInt("invoice_item_id"));
        item.setInvoiceId(rs.getInt("invoice_id"));
        item.setFeeTypeId(rs.getInt("fee_type_id"));
        item.setDescription(rs.getString("description"));
        item.setAmount(rs.getBigDecimal("amount"));
        return item;
    }

    @Override
    public Optional<InvoiceItem> findById(int id) {
        return queryOne(SELECT + " WHERE invoice_item_id = ?", MAPPER, id);
    }

    @Override
    public List<InvoiceItem> findAll() {
        return queryList(SELECT + " ORDER BY invoice_id", MAPPER);
    }

    /** The lines of one invoice. */
    public List<InvoiceItem> findByInvoice(int invoiceId) {
        return queryList(SELECT + " WHERE invoice_id = ? ORDER BY invoice_item_id",
                MAPPER, invoiceId);
    }

    /**
     * The lines added up, which is what the invoice total should equal.
     *
     * <p>Useful for checking a bill against its own lines.</p>
     */
    public BigDecimal sumByInvoice(int invoiceId) {
        return queryOne("SELECT ISNULL(SUM(amount), 0) FROM dbo.invoice_items WHERE invoice_id = ?",
                rs -> rs.getBigDecimal(1), invoiceId).orElse(BigDecimal.ZERO);
    }

    /** Removes every line of an invoice before it is rebuilt. */
    public int deleteByInvoice(Connection connection, int invoiceId) {
        return executeUpdate(connection,
                "DELETE FROM dbo.invoice_items WHERE invoice_id = ?", invoiceId);
    }

    @Override
    public int insert(InvoiceItem entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, InvoiceItem entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(InvoiceItem entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, InvoiceItem entity) {
        return executeUpdate(connection, UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean deleteById(int id) {
        return executeUpdate(DELETE, id) > 0;
    }

    @Override
    public boolean deleteById(Connection connection, int id) {
        return executeUpdate(connection, DELETE, id) > 0;
    }

    private Object[] insertParams(InvoiceItem entity) {
        return new Object[]{
                entity.getInvoiceId(),
                entity.getFeeTypeId(),
                entity.getDescription(),
                entity.getAmount()
        };
    }

    private Object[] updateParams(InvoiceItem entity) {
        return new Object[]{
                entity.getFeeTypeId(),
                entity.getDescription(),
                entity.getAmount(),
                entity.getInvoiceItemId()
        };
    }
}
