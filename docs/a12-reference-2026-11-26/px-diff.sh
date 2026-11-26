# px-diff.sh <raw> <out.sql>: for each raw frame i (0-based), compare it with frame i-1 byte by byte
# and write one SQL row: i, changed bytes below the status bar, the changed region's bounding box
# (x 0-179, y 0-399 in the 180x400 frame), the largest channel change (0-255), and changed bytes in
# the status bar (y < 12, 45 of 1,600 px). Frame 0 gets -1s.
RAW=$1; SQL=$2
FS=216020
T=/data/local/tmp/ref1126/tmp
mkdir -p $T
size=$(ls -l $RAW | tr -s ' ' | cut -d' ' -f5)
n=$((size / FS))
echo "create table px(i int, n int, x0 int, x1 int, y0 int, y1 int, maxd int, sb int);" > $SQL
echo "insert into px values (0, -1, -1, -1, -1, -1, -1, -1);" >> $SQL
dd if=$RAW of=$T/a bs=$FS skip=0 count=1 2>/dev/null
i=1
while [ $i -lt $n ]; do
  dd if=$RAW of=$T/b bs=$FS skip=$i count=1 2>/dev/null
  cmp -l $T/a $T/b 2>/dev/null | awk -v i=$i '
    function oct(s,  v, j) { v = 0; for (j = 1; j <= length(s); j++) v = v * 8 + substr(s, j, 1); return v }
    BEGIN { n = 0; sb = 0; md = 0; x0 = 999; x1 = -1; y0 = 999; y1 = -1 }
    { p = $1 - 21; if (p < 0) next; px = int(p / 3); y = int(px / 180); x = px % 180
      if (y < 12) { sb++; next }
      n++; d = oct($2) - oct($3); if (d < 0) d = -d; if (d > md) md = d
      if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
    END { if (n == 0) { x0 = -1; y0 = -1 }
      printf "insert into px values (%d, %d, %d, %d, %d, %d, %d, %d);\n", i, n, x0, x1, y0, y1, md, sb }' >> $SQL
  mv $T/b $T/a
  i=$((i + 1))
done
echo "frames=$n" >&2
