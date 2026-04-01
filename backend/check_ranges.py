import sqlite3

conn = sqlite3.connect('D:/ChildFocus - The Backup/database/childfocus.db')
rows = conn.execute('SELECT fcr, csv, att FROM segments WHERE segment_id != "S_THUMB"').fetchall()

if not rows:
    print('No segments in DB — need to run classify_full on videos first')
else:
    fcrs = [r[0] for r in rows]
    csvs = [r[1] for r in rows]
    atts = [r[2] for r in rows]
    print(f'FCR: min={min(fcrs):.4f}  max={max(fcrs):.4f}  mean={sum(fcrs)/len(fcrs):.4f}  n={len(fcrs)}')
    print(f'CSV: min={min(csvs):.4f}  max={max(csvs):.4f}  mean={sum(csvs)/len(csvs):.4f}  n={len(csvs)}')
    print(f'ATT: min={min(atts):.4f}  max={max(atts):.4f}  mean={sum(atts)/len(atts):.4f}  n={len(atts)}')

conn.close()
