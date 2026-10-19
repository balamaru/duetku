package core

import (
	"encoding/csv"
	"io"
	"strconv"
	"strings"
	"unicode"
)

func spreadsheetText(s string) string {
	trimmed := strings.TrimLeftFunc(s, func(r rune) bool { return unicode.IsSpace(r) || unicode.IsControl(r) || r == '\ufeff' })
	if len(trimmed) > 0 && strings.ContainsRune("=+-@", rune(trimmed[0])) {
		return "'" + s
	}
	return s
}

// ExportCSV writes to a caller-owned stream. Android owns URI access and cleanup.
// On any error the caller must discard the partial output and must not share it.
func (s *Service) ExportCSV(f Filter, destination io.Writer) (Summary, error) {
	if destination == nil {
		return Summary{}, fail("EXPORT_ERROR", "destination", "Tujuan ekspor wajib tersedia.")
	}
	var out Summary
	err := s.query(f, func(r Reader, rows []Transaction, _ Window) error {
		if len(rows) == 0 {
			return fail("NO_DATA", "filter", "Tidak ada transaksi untuk diekspor.")
		}
		var e error
		out, e = summarize(rows)
		if e != nil {
			return e
		}
		cats, e := r.Categories()
		if e != nil {
			return e
		}
		names := map[int64]string{}
		for _, c := range cats {
			names[c.ID] = c.Name
		}
		for _, t := range rows {
			if _, ok := names[t.CategoryID]; !ok {
				return fail("INVALID_DATA", "categoryId", "Kategori transaksi tidak ditemukan.")
			}
		}
		w := csv.NewWriter(destination)
		w.UseCRLF = true
		write := func(record []string) error {
			if e := w.Write(record); e != nil {
				return fail("EXPORT_ERROR", "destination", "Penulisan CSV gagal.")
			}
			return nil
		}
		if e = write([]string{"Tanggal", "Tipe", "Jenis", "Nominal", "Catatan"}); e != nil {
			return e
		}
		for _, t := range rows {
			if e = write([]string{t.Date, t.Type, spreadsheetText(names[t.CategoryID]), strconv.FormatInt(t.Amount, 10), spreadsheetText(t.Note)}); e != nil {
				return e
			}
		}
		for _, v := range []struct {
			label  string
			amount int64
		}{{"Total pemasukan", out.Income}, {"Total pengeluaran", out.Expense}, {"Saldo periode", out.Balance}} {
			if e = write([]string{"", "", v.label, strconv.FormatInt(v.amount, 10), ""}); e != nil {
				return e
			}
		}
		w.Flush()
		if w.Error() != nil {
			return fail("EXPORT_ERROR", "destination", "Penulisan CSV gagal.")
		}
		return nil
	})
	if err != nil {
		return Summary{}, err
	}
	return out, nil
}
