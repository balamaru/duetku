package core

import (
	"fmt"
	"math"
	"math/big"
	"sort"
	"strings"
	"time"
)

type ReportCategory struct {
	CategoryID int64
	Name       string
	Amount     int64
	Count      int
	Percent    string
}
type Trend struct {
	Label           string
	Income, Expense int64
}
type ReportTransaction struct {
	Transaction
	Category string
}
type Narrative struct {
	ID, Judul string
	Paragraf  []string
	Sumber    string
}
type ReportDataset struct {
	IncomeCategories                                           []ReportCategory
	Weekdays                                                   []ReportWeekday
	Changes                                                    []ReportChange
	Days, IncomeCount, ExpenseCount, NoExpenseDays             int
	Executive, IncomeExplanation, TrendExplanation, Conclusion string
	SchemaVersion                                              int
	Period, Retention                                          Window
	Generated, RecordedSince                                   string
	Summary                                                    Summary
	SavingRatio, DailyAverage                                  string
	Categories                                                 []ReportCategory
	Trends                                                     []Trend
	ComparisonAvailable                                        bool
	ComparisonPeriod                                           Window
	Previous                                                   Summary
	PreviousCategories                                         []ReportCategory
	PeakDay                                                    string
	PeakAmount                                                 int64
	SpikeDays                                                  int
	LargeTransactions                                          []ReportTransaction
	Weekday                                                    string
	Top                                                        []ReportTransaction
	Transactions                                               []ReportTransaction
	Notes                                                      []string
}
type ReportAnalyzer interface {
	Analyze(ReportDataset) []Narrative
}
type RuleBasedAnalyzer struct{}
type Report struct {
	Dataset   ReportDataset
	Narrative []Narrative
}

func decimalRatio(n, d int64, scale int64) string {
	if d == 0 {
		return "tidak tersedia"
	}
	r := new(big.Rat).SetFrac(big.NewInt(n), big.NewInt(d))
	r.Mul(r, new(big.Rat).SetInt64(scale))
	return strings.ReplaceAll(r.FloatString(1), ".", ",")
}
func rupiah(n int64) string {
	s := fmt.Sprint(n)
	negative := strings.HasPrefix(s, "-")
	s = strings.TrimPrefix(s, "-")
	for i := len(s) - 3; i > 0; i -= 3 {
		s = s[:i] + "." + s[i:]
	}
	if negative {
		s = "-" + s
	}
	return "Rp " + s
}
func reportCategories(rows []Transaction, cats map[int64]string, total int64) []ReportCategory {
	groups := map[int64]ReportCategory{}
	for _, t := range rows {
		if t.Type == "expense" {
			g := groups[t.CategoryID]
			g.CategoryID = t.CategoryID
			g.Name = cats[t.CategoryID]
			g.Amount += t.Amount
			g.Count++
			groups[t.CategoryID] = g
		}
	}
	out := []ReportCategory{}
	for _, g := range groups {
		g.Percent = decimalRatio(g.Amount, total, 100)
		out = append(out, g)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Amount == out[j].Amount {
			return out[i].CategoryID < out[j].CategoryID
		}
		return out[i].Amount > out[j].Amount
	})
	return out
}

// Report uses the same selection and integer summarizer as Dashboard/CSV.
func (s *Service) Report(f Filter, appendix, notes bool) (Report, error) {
	var result Report
	w, now, err := s.window()
	if err != nil {
		return result, err
	}
	requested := f
	f, err = normalizeFilter(f, w)
	if err != nil {
		return result, err
	}
	err = s.repo.Read(func(r Reader) error {
		all, e := r.Transactions()
		if e != nil {
			return e
		}
		rows := selectRows(all, f)
		if len(rows) == 0 {
			return fail("EMPTY_REPORT", "period", "Tidak ada transaksi pada periode ini.")
		}
		summary, e := summarize(rows)
		if e != nil {
			return e
		}
		categories, e := r.Categories()
		if e != nil {
			return e
		}
		names := map[int64]string{}
		for _, c := range categories {
			names[c.ID] = c.Name
		}
		start, _ := parseDate(f.From)
		end, _ := parseDate(f.To)
		days := int(end.Sub(start).Hours()/24) + 1
		d := ReportDataset{SchemaVersion: 1, Period: Window{f.From, f.To}, Retention: w, Generated: now.Format("2006-01-02"), Summary: summary,
			SavingRatio: decimalRatio(summary.Balance, summary.Income, 100), DailyAverage: decimalRatio(summary.Expense, int64(days), 1), Categories: reportCategories(rows, names, summary.Expense),
			Trends: []Trend{}, Top: []ReportTransaction{}, Transactions: []ReportTransaction{}, LargeTransactions: []ReportTransaction{}, Notes: []string{}}
		for _, t := range all {
			if t.Date >= w.Start && t.Date <= w.End && (d.RecordedSince == "" || t.Date < d.RecordedSince) {
				d.RecordedSince = t.Date
			}
		}
		if d.RecordedSince > f.From {
			d.Notes = append(d.Notes, "Data tercatat sejak "+d.RecordedSince+". Rata-rata bersifat indikatif.")
		}
		if requested.From != "" && requested.From < f.From || requested.To != "" && requested.To > f.To {
			d.Notes = append(d.Notes, "Periode dipangkas mengikuti jendela retensi dan tanggal hari ini.")
		}
		prevEnd := start.AddDate(0, 0, -1)
		prevStart := start.AddDate(0, 0, -days)
		d.ComparisonPeriod = Window{prevStart.Format("2006-01-02"), prevEnd.Format("2006-01-02")}
		d.ComparisonAvailable = d.ComparisonPeriod.Start >= w.Start
		if d.ComparisonAvailable {
			pf := f
			pf.From = d.ComparisonPeriod.Start
			pf.To = d.ComparisonPeriod.End
			previous := selectRows(all, pf)
			d.Previous, e = summarize(previous)
			if e != nil {
				return e
			}
			d.PreviousCategories = reportCategories(previous, names, d.Previous.Expense)
		} else {
			d.Notes = append(d.Notes, "Pembanding tidak tersedia: sebagian periode berada di luar retensi.")
		}
		daily := map[string]int64{}
		buckets := map[string]Trend{}
		categoryRows := map[int64][]ReportTransaction{}
		key := func(t time.Time) string {
			if days > 120 {
				return t.Format("2006-01")
			}
			if days > 31 {
				offset := (int(t.Weekday()) + 6) % 7
				return t.AddDate(0, 0, -offset).Format("2006-01-02")
			}
			return t.Format("2006-01-02")
		}
		for date := start; !date.After(end); date = date.AddDate(0, 0, 1) {
			k := key(date)
			buckets[k] = Trend{Label: k}
			daily[date.Format("2006-01-02")] = 0
		}
		expenses := []ReportTransaction{}
		for _, t := range rows {
			date, _ := parseDate(t.Date)
			k := key(date)
			bucket := buckets[k]
			if t.Type == "income" {
				bucket.Income += t.Amount
			} else {
				bucket.Expense += t.Amount
				daily[t.Date] += t.Amount
			}
			buckets[k] = bucket
			if !notes {
				t.Note = ""
			}
			rt := ReportTransaction{t, names[t.CategoryID]}
			if appendix && len(d.Transactions) < 2000 {
				d.Transactions = append(d.Transactions, rt)
			}
			if t.Type == "expense" {
				expenses = append(expenses, rt)
				categoryRows[t.CategoryID] = append(categoryRows[t.CategoryID], rt)
			}
		}
		keys := []string{}
		for k := range buckets {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			d.Trends = append(d.Trends, buckets[k])
		}
		if appendix && len(rows) > 2000 {
			d.Notes = append(d.Notes, "Lampiran dibatasi 2.000 transaksi terbaru; gunakan CSV untuk seluruh baris.")
		}
		sort.SliceStable(expenses, func(i, j int) bool { return expenses[i].Amount > expenses[j].Amount })
		limit := len(expenses)
		if limit > 5 {
			limit = 5
		}
		d.Top = expenses[:limit]
		var weekdaySums [7]float64
		var weekdayCounts [7]int
		mean := float64(summary.Expense) / float64(days)
		variance := 0.0
		for date := start; !date.After(end); date = date.AddDate(0, 0, 1) {
			label := date.Format("2006-01-02")
			amount := daily[label]
			if amount > d.PeakAmount {
				d.PeakAmount = amount
				d.PeakDay = label
			}
			delta := float64(amount) - mean
			variance += delta * delta
			weekdaySums[date.Weekday()] += float64(amount)
			weekdayCounts[date.Weekday()]++
		}
		// Statistical floating point is used only for pattern thresholds, never financial totals.
		if days >= 14 && d.RecordedSince <= f.From {
			threshold := mean + 2*math.Sqrt(variance/float64(days))
			for _, amount := range daily {
				if float64(amount) > threshold {
					d.SpikeDays++
				}
			}
		}
		if days >= 28 && d.RecordedSince <= f.From && summary.Expense > 0 {
			best := 0
			for i := 1; i < 7; i++ {
				if weekdaySums[i]/float64(weekdayCounts[i]) > weekdaySums[best]/float64(weekdayCounts[best]) {
					best = i
				}
			}
			d.Weekday = []string{"Minggu", "Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu"}[best]
		}
		ids := []int64{}
		for id := range categoryRows {
			ids = append(ids, id)
		}
		sort.Slice(ids, func(i, j int) bool { return ids[i] < ids[j] })
		for _, id := range ids {
			items := categoryRows[id]
			if len(items) < 5 {
				continue
			}
			sort.SliceStable(items, func(i, j int) bool { return items[i].Amount < items[j].Amount })
			quantile := func(p float64) float64 {
				x := p * float64(len(items)-1)
				lo := int(x)
				hi := int(math.Ceil(x))
				return float64(items[lo].Amount) + (float64(items[hi].Amount-items[lo].Amount))*(x-float64(lo))
			}
			median := quantile(.5)
			q1 := quantile(.25)
			q3 := quantile(.75)
			for _, t := range items {
				if float64(t.Amount) >= 3*median && float64(t.Amount) >= q3+1.5*(q3-q1) {
					d.LargeTransactions = append(d.LargeTransactions, t)
				}
			}
		}
		d.Notes = append(d.Notes, "Analisis otomatis lokal, bukan nasihat keuangan. Salinan ekspor di luar aplikasi tidak ikut retensi otomatis.")
		enrichReport(&d, rows, names, daily, start, end)
		result = Report{d, RuleBasedAnalyzer{}.Analyze(d)}
		return nil
	})
	if err != nil {
		return Report{}, safe(err)
	}
	return result, nil
}
func (RuleBasedAnalyzer) Analyze(d ReportDataset) []Narrative {
	out := []Narrative{}
	add := func(id, text string) {
		out = append(out, Narrative{id, "Penjelasan pengeluaran", []string{text}, "rule"})
	}
	if len(d.Categories) > 0 {
		c := d.Categories[0]
		add("dominant", fmt.Sprintf("%s menyumbang %s%% pengeluaran (%s) dan menjadi kategori terbesar.", c.Name, c.Percent, rupiah(c.Amount)))
	}
	if len(d.Categories) >= 3 {
		total := d.Categories[0].Amount + d.Categories[1].Amount + d.Categories[2].Amount
		if new(big.Int).Mul(big.NewInt(total), big.NewInt(100)).Cmp(new(big.Int).Mul(big.NewInt(d.Summary.Expense), big.NewInt(70))) >= 0 {
			add("concentration", "Tiga kategori teratas mencakup "+decimalRatio(total, d.Summary.Expense, 100)+"% total pengeluaran.")
		}
	}
	if d.ComparisonAvailable {
		delta := d.Summary.Expense - d.Previous.Expense
		if d.Previous.Expense == 0 {
			add("total_change", "Pengeluaran periode sebelumnya nol; perubahan nominal "+rupiah(delta)+". Persentase tidak tersedia.")
		} else {
			add("total_change", "Perubahan total pengeluaran dibanding periode sebelumnya: "+decimalRatio(delta, d.Previous.Expense, 100)+"% ("+rupiah(delta)+").")
		}
		previous := map[int64]ReportCategory{}
		for _, c := range d.PreviousCategories {
			previous[c.CategoryID] = c
		}
		current := map[int64]ReportCategory{}
		for _, c := range d.Categories {
			current[c.CategoryID] = c
		}
		ids := []int64{}
		for id := range previous {
			ids = append(ids, id)
		}
		sort.Slice(ids, func(i, j int) bool { return ids[i] < ids[j] })
		for _, id := range ids {
			p := previous[id]
			c := current[id]
			delta := c.Amount - p.Amount
			absolute := delta
			if absolute < 0 {
				absolute = -absolute
			}
			if absolute >= 50000 && new(big.Int).Mul(big.NewInt(absolute), big.NewInt(100)).Cmp(new(big.Int).Mul(big.NewInt(p.Amount), big.NewInt(20))) >= 0 {
				add("category_change", p.Name+": perubahan "+decimalRatio(delta, p.Amount, 100)+"% ("+rupiah(delta)+") dibanding periode sebelumnya.")
			}
		}
	}
	if d.PeakAmount > 0 {
		add("peak", "Pengeluaran tertinggi terjadi pada "+d.PeakDay+" ("+rupiah(d.PeakAmount)+").")
	}
	if d.SpikeDays > 0 {
		add("spike", fmt.Sprintf("Terdapat %d hari dengan pengeluaran jauh di atas biasanya.", d.SpikeDays))
	}
	for _, t := range d.LargeTransactions {
		add("large", "Transaksi "+t.Category+" "+rupiah(t.Amount)+" pada "+t.Date+" jauh di atas biasanya.")
	}
	if d.Weekday != "" {
		add("weekday", "Pengeluaran cenderung tertinggi pada hari "+d.Weekday+".")
	}
	if d.Summary.Balance < 0 {
		add("deficit", "Pengeluaran melebihi pemasukan sebesar "+rupiah(-d.Summary.Balance)+".")
	}
	if d.Summary.Count < 5 || d.RecordedSince > d.Period.Start {
		add("insufficient", "Data belum cukup untuk analisis pola.")
	}
	return out
}
