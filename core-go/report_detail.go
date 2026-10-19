package core

import (
	"fmt"
	"sort"
	"time"
)

type ReportWeekday struct {
	Name    string
	Amount  int64
	Days    int
	Average string
}
type ReportChange struct {
	Name                     string
	Previous, Current, Delta int64
	Percent                  string
}

// Enrich the same selected snapshot; amounts remain integer rupiah throughout.
func enrichReport(d *ReportDataset, rows []Transaction, names map[int64]string, daily map[string]int64, start, end time.Time) {
	d.Days = int(end.Sub(start).Hours()/24) + 1
	incomes := []Transaction{}
	for _, row := range rows {
		if row.Type == "income" {
			d.IncomeCount++
			row.Type = "expense"
			incomes = append(incomes, row)
		} else {
			d.ExpenseCount++
		}
	}
	d.IncomeCategories = reportCategories(incomes, names, d.Summary.Income)
	d.Weekdays = []ReportWeekday{}
	var amounts [7]int64
	var counts [7]int
	for day := start; !day.After(end); day = day.AddDate(0, 0, 1) {
		amount := daily[day.Format("2006-01-02")]
		if amount == 0 {
			d.NoExpenseDays++
		}
		index := (int(day.Weekday()) + 6) % 7
		amounts[index] += amount
		counts[index]++
	}
	for i, name := range []string{"Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu", "Minggu"} {
		d.Weekdays = append(d.Weekdays, ReportWeekday{name, amounts[i], counts[i], decimalRatio(amounts[i], int64(counts[i]), 1)})
	}
	d.Changes = []ReportChange{}
	if d.ComparisonAvailable {
		current, previous, labels := map[int64]int64{}, map[int64]int64{}, map[int64]string{}
		for _, c := range d.Categories {
			current[c.CategoryID] = c.Amount
			labels[c.CategoryID] = c.Name
		}
		for _, c := range d.PreviousCategories {
			previous[c.CategoryID] = c.Amount
			labels[c.CategoryID] = c.Name
		}
		for id, name := range labels {
			delta := current[id] - previous[id]
			d.Changes = append(d.Changes, ReportChange{name, previous[id], current[id], delta, decimalRatio(delta, previous[id], 100)})
		}
		sort.Slice(d.Changes, func(i, j int) bool {
			a, b := d.Changes[i], d.Changes[j]
			if a.Delta == b.Delta {
				return a.Name < b.Name
			}
			return a.Delta > b.Delta
		})
		if d.RecordedSince > d.ComparisonPeriod.Start {
			d.Notes = append(d.Notes, "Pembanding berada dalam retensi, tetapi catatan tersedia baru sejak "+d.RecordedSince+". Perubahan bukan bukti seluruh aktivitas keuangan.")
		}
	}
	d.Executive = fmt.Sprintf("Dalam %d hari, tercatat pemasukan %s dan pengeluaran %s. Arus kas bersih periode ini %s.", d.Days, rupiah(d.Summary.Income), rupiah(d.Summary.Expense), rupiah(d.Summary.Balance))
	if d.Summary.Income > 0 {
		d.Executive += " Rasio tabungan terhadap pemasukan tercatat sebesar " + d.SavingRatio + "%."
	}
	if len(d.Categories) > 0 {
		c := d.Categories[0]
		d.Executive += " Pengeluaran terbesar adalah " + c.Name + " (" + c.Percent + "%)."
	}
	d.IncomeExplanation = "Belum ada pemasukan tercatat pada periode ini. Rasio tabungan tidak tersedia."
	if len(d.IncomeCategories) > 0 {
		c := d.IncomeCategories[0]
		d.IncomeExplanation = fmt.Sprintf("%s menyumbang %s%% pemasukan (%s), dari %d transaksi. Komposisi menunjukkan sumber pemasukan yang tercatat, bukan jaminan pendapatan berulang.", c.Name, c.Percent, rupiah(c.Amount), c.Count)
	}
	d.TrendExplanation = "Belum ada bucket tren."
	if len(d.Trends) > 0 {
		peak := d.Trends[0]
		active := 0
		for _, v := range d.Trends {
			if v.Expense > peak.Expense {
				peak = v
			}
			if v.Income > 0 {
				active++
			}
		}
		d.TrendExplanation = fmt.Sprintf("Pengeluaran terbesar per bucket tercatat pada %s sebesar %s. Pemasukan tercatat di %d dari %d bucket. Bucket pertama dan terakhir mengikuti batas periode.", peak.Label, rupiah(peak.Expense), active, len(d.Trends))
	}
	d.Conclusion = d.Executive + " Tinjau kategori terbesar serta transaksi utama sebelum menentukan penyesuaian pengeluaran."
	d.Notes = append(d.Notes, "Pola hari memakai seluruh pengeluaran, dibagi jumlah kemunculan hari kalender (termasuk hari tanpa transaksi). Kategori rutin/fleksibel tidak ditebak dari nama kategori atau catatan.")
}
