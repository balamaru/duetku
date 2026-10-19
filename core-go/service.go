package core

import (
	"errors"
	"sort"
	"strings"
	"time"
	"unicode/utf8"
)

// Reader must expose a consistent snapshot. Missing IDs return NOT_FOUND.
type Reader interface {
	Categories() ([]Category, error)
	Category(int64) (Category, error)
	Transactions() ([]Transaction, error)
	Transaction(string) (Transaction, error)
}
type Writer interface {
	Reader
	SaveCategory(Category) (Category, error)
	DeleteCategory(int64) error
	SaveTransaction(Transaction) (Transaction, error)
	DeleteTransaction(string) error
}

// Write commits only on nil; any callback/storage error must roll back all changes.
// Implementations serialize writes and keep readers on consistent snapshots.
type Repository interface {
	Read(func(Reader) error) error
	Write(func(Writer) error) error
}
type Service struct {
	repo  Repository
	clock Clock
}

func New(repo Repository, clock Clock) (*Service, error) {
	if repo == nil || clock == nil {
		return nil, fail("INVALID_CONFIGURATION", "", "Repository dan jam wajib tersedia.")
	}
	return &Service{repo, clock}, nil
}
func safe(err error) error {
	if err == nil {
		return nil
	}
	var e *Error
	if errors.As(err, &e) {
		return e
	}
	return fail("STORAGE_ERROR", "", "Penyimpanan lokal gagal. Silakan coba lagi.")
}
func (s *Service) window() (Window, time.Time, error) {
	now := s.clock.Now()
	w, err := Retention(now.Format("2006-01-02"))
	return w, now, err
}
func (s *Service) RetentionWindow() (Window, error) { w, _, err := s.window(); return w, err }
func (s *Service) SaveTransaction(id string, in Input) (Transaction, error) {
	if id != "" && !ValidUUID(id) {
		return Transaction{}, fail("INVALID_ID", "id", "ID tidak sah.")
	}
	w, now, err := s.window()
	if err != nil {
		return Transaction{}, err
	}
	var result Transaction
	err = s.repo.Write(func(tx Writer) error {
		t := Transaction{Input: in, CreatedAt: now.UTC().Format(time.RFC3339Nano)}
		if id != "" {
			old, e := tx.Transaction(id)
			if e != nil {
				return e
			}
			if old.Date < w.Start || old.Date > w.End {
				return fail("OUTSIDE_RETENTION", "id", "Transaksi di luar jendela retensi.")
			}
			t.ID = id
			t.CreatedAt = old.CreatedAt
		}
		cat, e := tx.Category(in.CategoryID)
		if e != nil {
			return e
		}
		if e = ValidateInput(in, cat, w); e != nil {
			return e
		}
		t.UpdatedAt = now.UTC().Format(time.RFC3339Nano)
		result, e = tx.SaveTransaction(t)
		return e
	})
	if err != nil {
		return Transaction{}, safe(err)
	}
	return result, nil
}
func (s *Service) ListCategories() ([]Category, error) {
	var result []Category
	err := s.repo.Read(func(r Reader) error { var e error; result, e = r.Categories(); return e })
	if err != nil {
		return nil, safe(err)
	}
	sort.Slice(result, func(i, j int) bool { return result[i].ID < result[j].ID })
	return result, nil
}
func (s *Service) SaveCategory(id int64, name, kind string, active bool) (Category, error) {
	name = strings.TrimSpace(name)
	if id < 0 || !utf8.ValidString(name) || utf8.RuneCountInString(name) < 1 || utf8.RuneCountInString(name) > 50 || !validType(kind) {
		return Category{}, fail("INVALID_CATEGORY", "category", "Nama kategori wajib 1–50 karakter dan jenis harus sah.")
	}
	var out Category
	err := s.repo.Write(func(tx Writer) error {
		c := Category{ID: id, Name: name, Type: kind, Active: active}
		if id != 0 {
			old, e := tx.Category(id)
			if e != nil {
				return e
			}
			if old.Type != kind {
				return fail("IMMUTABLE_TYPE", "type", "Jenis kategori tidak dapat diubah.")
			}
			c.Default = old.Default
		}
		cats, e := tx.Categories()
		if e != nil {
			return e
		}
		for _, other := range cats {
			if other.ID != id && other.Type == kind && strings.EqualFold(other.Name, name) {
				return fail("DUPLICATE_CATEGORY", "name", "Nama kategori sudah digunakan pada jenis ini.")
			}
		}
		out, e = tx.SaveCategory(c)
		return e
	})
	if err != nil {
		return Category{}, safe(err)
	}
	return out, nil
}

// DeleteCategory deactivates referenced categories, deletes unreferenced ones.
func (s *Service) DeleteCategory(id int64) (bool, error) {
	deactivated := false
	err := s.repo.Write(func(tx Writer) error {
		c, e := tx.Category(id)
		if e != nil {
			return e
		}
		rows, e := tx.Transactions()
		if e != nil {
			return e
		}
		for _, t := range rows {
			if t.CategoryID == id {
				c.Active = false
				_, e = tx.SaveCategory(c)
				deactivated = true
				return e
			}
		}
		return tx.DeleteCategory(id)
	})
	if err != nil {
		return false, safe(err)
	}
	return deactivated, nil
}

// SeedCategories runs on database creation only, in the same initialization flow.
func (s *Service) SeedCategories() error {
	return safe(s.repo.Write(func(tx Writer) error {
		cats, e := tx.Categories()
		if e != nil {
			return e
		}
		for _, group := range []struct {
			kind  string
			names []string
		}{{"income", []string{"Gaji", "Bonus", "Lainnya"}}, {"expense", []string{"Makanan", "Transportasi", "Tagihan", "Hiburan", "Lainnya"}}} {
			for _, name := range group.names {
				found := false
				for _, c := range cats {
					if c.Type == group.kind && strings.EqualFold(c.Name, name) {
						found = true
						break
					}
				}
				if !found {
					if _, e = tx.SaveCategory(Category{Name: name, Type: group.kind, Active: true, Default: true}); e != nil {
						return e
					}
				}
			}
		}
		return nil
	}))
}
func (s *Service) query(f Filter, visit func(Reader, []Transaction, Window) error) error {
	w, _, err := s.window()
	if err != nil {
		return err
	}
	f, err = normalizeFilter(f, w)
	if err != nil {
		return err
	}
	return safe(s.repo.Read(func(r Reader) error {
		rows, e := r.Transactions()
		if e != nil {
			return e
		}
		return visit(r, selectRows(rows, f), w)
	}))
}
func (s *Service) ListTransactions(f Filter) (Page, error) {
	out := Page{}
	err := s.query(f, func(_ Reader, rows []Transaction, w Window) error {
		out.Total = len(rows)
		out.Window = w
		start := f.Offset
		if start > len(rows) {
			start = len(rows)
		}
		limit := f.Limit
		if limit == 0 {
			limit = 20
		}
		end := len(rows)
		if limit < end-start {
			end = start + limit
		}
		out.Items = rows[start:end]
		return nil
	})
	if err != nil {
		return Page{}, err
	}
	return out, nil
}
func (s *Service) GetSummary(f Filter) (Summary, error) {
	var out Summary
	err := s.query(f, func(_ Reader, rows []Transaction, _ Window) error { var e error; out, e = summarize(rows); return e })
	if err != nil {
		return Summary{}, err
	}
	return out, nil
}
func (s *Service) GetBreakdown(f Filter) ([]Breakdown, error) {
	out := make([]Breakdown, 0)
	err := s.query(f, func(r Reader, rows []Transaction, _ Window) error {
		if _, e := summarize(rows); e != nil {
			return e
		}
		groups := map[int64]Breakdown{}
		for _, t := range rows {
			b, ok := groups[t.CategoryID]
			if !ok {
				c, e := r.Category(t.CategoryID)
				if e != nil {
					return e
				}
				b = Breakdown{CategoryID: c.ID, Name: c.Name, Type: c.Type}
			}
			var e error
			b.Amount, e = add(b.Amount, t.Amount)
			if e != nil {
				return e
			}
			groups[t.CategoryID] = b
		}
		for _, b := range groups {
			out = append(out, b)
		}
		sort.Slice(out, func(i, j int) bool { return out[i].CategoryID < out[j].CategoryID })
		return nil
	})
	if err != nil {
		return nil, err
	}
	return out, nil
}

type DeleteScope struct {
	Kind     string
	ID       string
	From, To string
}
type DeletePreview struct {
	Scope  DeleteScope
	Count  int
	Window Window
}

func deletePredicate(scope DeleteScope) (func(Transaction) bool, error) {
	switch scope.Kind {
	case "one":
		if ValidUUID(scope.ID) && scope.From == "" && scope.To == "" {
			return func(t Transaction) bool { return t.ID == scope.ID }, nil
		}
	case "all":
		if scope.ID == "" && scope.From == "" && scope.To == "" {
			return func(Transaction) bool { return true }, nil
		}
	case "range":
		if _, e := parseDate(scope.From); e != nil {
			return nil, e
		}
		if _, e := parseDate(scope.To); e != nil {
			return nil, e
		}
		if scope.ID == "" && scope.From <= scope.To {
			return func(t Transaction) bool { return t.Date >= scope.From && t.Date <= scope.To }, nil
		}
	}
	return nil, fail("INVALID_DELETE_SCOPE", "scope", "Pilihan penghapusan tidak sah.")
}
func (s *Service) PreviewDelete(scope DeleteScope) (DeletePreview, error) {
	p, e := deletePredicate(scope)
	if e != nil {
		return DeletePreview{}, e
	}
	w, _, e := s.window()
	if e != nil {
		return DeletePreview{}, e
	}
	out := DeletePreview{Scope: scope, Window: w}
	e = s.repo.Read(func(r Reader) error {
		rows, e := r.Transactions()
		if e != nil {
			return e
		}
		for _, t := range rows {
			if p(t) {
				out.Count++
			}
		}
		return nil
	})
	if e != nil {
		return DeletePreview{}, safe(e)
	}
	return out, nil
}

// UI must confirm this exact scope and count. A changed count requires a new preview.
func (s *Service) DeleteHistory(preview DeletePreview, confirmed bool) (int, error) {
	if !confirmed {
		return 0, fail("CONFIRMATION_REQUIRED", "confirmed", "Konfirmasi diperlukan; penghapusan tidak dapat dibatalkan.")
	}
	p, e := deletePredicate(preview.Scope)
	if e != nil {
		return 0, e
	}
	w, _, e := s.window()
	if e != nil {
		return 0, e
	}
	if w != preview.Window {
		return 0, fail("STALE_PREVIEW", "preview", "Tanggal berubah; tinjau ulang penghapusan.")
	}
	count := 0
	e = s.repo.Write(func(tx Writer) error {
		rows, e := tx.Transactions()
		if e != nil {
			return e
		}
		for _, t := range rows {
			if p(t) {
				count++
			}
		}
		if count != preview.Count {
			return fail("STALE_PREVIEW", "preview", "Jumlah transaksi berubah; tinjau ulang penghapusan.")
		}
		for _, t := range rows {
			if p(t) {
				if e = tx.DeleteTransaction(t.ID); e != nil {
					return e
				}
			}
		}
		return nil
	})
	if e != nil {
		return 0, safe(e)
	}
	return count, nil
}
func (s *Service) PurgeExpiredHistory() (int, error) {
	w, _, err := s.window()
	if err != nil {
		return 0, err
	}
	count := 0
	err = s.repo.Write(func(tx Writer) error {
		rows, e := tx.Transactions()
		if e != nil {
			return e
		}
		for _, t := range rows {
			if t.Date < w.Start {
				if e = tx.DeleteTransaction(t.ID); e != nil {
					return e
				}
				count++
			}
		}
		return nil
	})
	if err != nil {
		return 0, safe(err)
	}
	return count, nil
}
