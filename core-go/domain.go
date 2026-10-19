// Package core implements the offline domain contract. It performs no network or filesystem I/O.
package core

import (
	"math"
	"sort"
	"strings"
	"time"
	"unicode/utf8"
)

const MaxAmount int64 = 9999999999999

type Error struct{ Code, Field, Message string }

func (e *Error) Error() string               { return e.Message }
func fail(code, field, message string) error { return &Error{code, field, message} }

type Clock interface{ Now() time.Time }
type Window struct{ Start, End string }
type Category struct {
	ID              int64
	Name, Type      string
	Active, Default bool
}
type Input struct {
	CategoryID int64
	Type       string
	Amount     int64
	Date, Note string
}
type Transaction struct {
	ID string
	Input
	CreatedAt, UpdatedAt string
}
type Filter struct {
	From, To, Type       string
	CategoryID           int64
	MinAmount, MaxAmount int64
	Search               string
	Offset, Limit        int
}
type Summary struct {
	Income, Expense, Balance int64
	Count                    int
}
type Breakdown struct {
	CategoryID int64
	Name, Type string
	Amount     int64
}
type Page struct {
	Items  []Transaction
	Total  int
	Window Window
}

func parseDate(value string) (time.Time, error) {
	t, err := time.Parse("2006-01-02", value)
	if err != nil || len(value) != 10 || t.Year() < 1 {
		return time.Time{}, fail("INVALID_DATE", "date", "Tanggal tidak sah; gunakan YYYY-MM-DD.")
	}
	return t, nil
}

// Retention clamps February 29 to February 28 before subtracting two days.
// UTC is used only for civil-date arithmetic, never to derive the user's date.
func Retention(today string) (Window, error) {
	t, err := parseDate(today)
	if err != nil {
		return Window{}, err
	}
	if t.Year() < 2 {
		return Window{}, fail("INVALID_DATE", "date", "Tahun perangkat tidak didukung.")
	}
	y, m, d := t.Date()
	last := time.Date(y-1, m+1, 0, 0, 0, 0, 0, time.UTC).Day()
	if d > last {
		d = last
	}
	start := time.Date(y-1, m, d, 0, 0, 0, 0, time.UTC).AddDate(0, 0, -2)
	if start.Year() < 1 {
		return Window{}, fail("INVALID_DATE", "date", "Tahun perangkat tidak didukung.")
	}
	return Window{start.Format("2006-01-02"), today}, nil
}

func validType(t string) bool { return t == "income" || t == "expense" }
func ValidateInput(in Input, cat Category, w Window) error {
	if !validType(in.Type) {
		return fail("INVALID_TYPE", "type", "Jenis transaksi tidak sah.")
	}
	if in.Amount < 1 || in.Amount > MaxAmount {
		return fail("INVALID_AMOUNT", "amount", "Nominal harus positif dan maksimal 13 digit rupiah.")
	}
	if _, err := parseDate(in.Date); err != nil {
		return err
	}
	if in.Date < w.Start || in.Date > w.End {
		return fail("OUTSIDE_RETENTION", "date", "Tanggal di luar jendela 12 bulan kalender + 3 hari.")
	}
	if !utf8.ValidString(in.Note) || utf8.RuneCountInString(in.Note) > 255 {
		return fail("INVALID_NOTE", "note", "Catatan maksimal 255 karakter.")
	}
	if in.CategoryID <= 0 || cat.ID != in.CategoryID {
		return fail("NOT_FOUND", "categoryId", "Kategori tidak ditemukan.")
	}
	if !cat.Active {
		return fail("INACTIVE_CATEGORY", "categoryId", "Kategori tidak aktif.")
	}
	if cat.Type != in.Type {
		return fail("CATEGORY_TYPE_MISMATCH", "categoryId", "Kategori tidak sesuai jenis transaksi.")
	}
	return nil
}

func normalizeFilter(f Filter, w Window) (Filter, error) {
	if f.From == "" {
		f.From = w.Start
	}
	if f.To == "" {
		f.To = w.End
	}
	if _, err := parseDate(f.From); err != nil {
		return f, err
	}
	if _, err := parseDate(f.To); err != nil {
		return f, err
	}
	if f.From > f.To || f.CategoryID < 0 || f.MinAmount < 0 || f.MaxAmount < 0 || (f.MaxAmount != 0 && f.MinAmount > f.MaxAmount) || f.Offset < 0 || f.Limit < 0 || f.Limit > 100 || (f.Type != "" && !validType(f.Type)) || !utf8.ValidString(f.Search) || utf8.RuneCountInString(f.Search) > 255 {
		return f, fail("INVALID_FILTER", "filter", "Filter tidak sah.")
	}
	if f.From < w.Start {
		f.From = w.Start
	}
	if f.To > w.End {
		f.To = w.End
	}
	if f.Limit == 0 {
		f.Limit = 20
	}
	return f, nil
}

func matches(t Transaction, f Filter) bool {
	return t.Date >= f.From && t.Date <= f.To && (f.Type == "" || t.Type == f.Type) && (f.CategoryID == 0 || t.CategoryID == f.CategoryID) && t.Amount >= f.MinAmount && (f.MaxAmount == 0 || t.Amount <= f.MaxAmount) && strings.Contains(strings.ToLower(t.Note), strings.ToLower(f.Search))
}
func selectRows(rows []Transaction, f Filter) []Transaction {
	out := make([]Transaction, 0)
	for _, t := range rows {
		if matches(t, f) {
			out = append(out, t)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Date == out[j].Date {
			if out[i].CreatedAt != out[j].CreatedAt {
				return out[i].CreatedAt > out[j].CreatedAt
			}
			return out[i].ID > out[j].ID
		}
		return out[i].Date > out[j].Date
	})
	return out
}
func add(a, b int64) (int64, error) {
	if b < 0 || a > math.MaxInt64-b {
		return 0, fail("AMOUNT_OVERFLOW", "amount", "Total nominal melampaui batas perhitungan.")
	}
	return a + b, nil
}
func summarize(rows []Transaction) (Summary, error) {
	s := Summary{Count: len(rows)}
	for _, t := range rows {
		if !validType(t.Type) || t.Amount < 1 || t.Amount > MaxAmount {
			return Summary{}, fail("INVALID_DATA", "transaction", "Data transaksi tidak sah.")
		}
		var err error
		if t.Type == "income" {
			s.Income, err = add(s.Income, t.Amount)
		} else {
			s.Expense, err = add(s.Expense, t.Amount)
		}
		if err != nil {
			return Summary{}, err
		}
	}
	s.Balance = s.Income - s.Expense
	return s, nil
}
