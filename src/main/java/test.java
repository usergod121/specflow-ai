// @requirement R001
public class test {
    public static String add(String a, String b) {
        boolean negA = a.startsWith("-");
        boolean negB = b.startsWith("-");
        String da = negA ? a.substring(1) : a;
        String db = negB ? b.substring(1) : b;

        if (negA == negB) {
            String sum = addAbs(da, db);
            if (sum.equals("0")) {
                return "0";
            }
            return negA ? "-" + sum : sum;
        }

        int cmp = compareAbs(da, db);
        if (cmp == 0) {
            return "0";
        }
        if (cmp > 0) {
            String diff = subAbs(da, db);
            return negA ? "-" + diff : diff;
        } else {
            String diff = subAbs(db, da);
            return negB ? "-" + diff : diff;
        }
    }

    private static String addAbs(String a, String b) {
        StringBuilder sb = new StringBuilder();
        int i = a.length() - 1;
        int j = b.length() - 1;
        int carry = 0;
        while (i >= 0 || j >= 0 || carry > 0) {
            int x = i >= 0 ? a.charAt(i) - '0' : 0;
            int y = j >= 0 ? b.charAt(j) - '0' : 0;
            int s = x + y + carry;
            sb.append((char) ('0' + s % 10));
            carry = s / 10;
            i--;
            j--;
        }
        return sb.reverse().toString();
    }

    private static String subAbs(String a, String b) {
        StringBuilder sb = new StringBuilder();
        int i = a.length() - 1;
        int j = b.length() - 1;
        int borrow = 0;
        while (i >= 0) {
            int x = a.charAt(i) - '0' - borrow;
            int y = j >= 0 ? b.charAt(j) - '0' : 0;
            if (x < y) {
                x += 10;
                borrow = 1;
            } else {
                borrow = 0;
            }
            sb.append((char) ('0' + (x - y)));
            i--;
            j--;
        }
        while (sb.length() > 1 && sb.charAt(sb.length() - 1) == '0') {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.reverse().toString();
    }

    private static int compareAbs(String a, String b) {
        if (a.length() != b.length()) {
            return a.length() > b.length() ? 1 : -1;
        }
        return a.compareTo(b);
    }
}