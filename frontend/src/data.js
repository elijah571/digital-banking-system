export const initialAccounts = [
  {
    id: 'acc-main',
    name: 'Main savings',
    lastFour: '8070',
    number: '3693618070',
    type: 'Savings',
    balance: 3650250,
    status: 'Active',
    accent: 'gold',
  },
  {
    id: 'acc-wallet',
    name: 'Everyday wallet',
    lastFour: '1942',
    number: '3693611942',
    type: 'Current',
    balance: 1200000,
    status: 'Active',
    accent: 'gold-muted',
  },
];

export const initialTransactions = [
  { id: 'TXN-849217', title: 'Transfer to Adaeze Okafor', subtitle: 'Access Bank · 10 Nov, 09:42', type: 'Transfer', amount: -25000, status: 'Success' },
  { id: 'DEP-883140', title: 'Card top-up', subtitle: 'Visa · 09 Nov, 17:20', type: 'Deposit', amount: 150000, status: 'Success' },
  { id: 'PAY-220418', title: 'Internet subscription', subtitle: 'FiberNet · 08 Nov, 12:08', type: 'Payment', amount: -18500, status: 'Success' },
  { id: 'WDL-193775', title: 'Bank withdrawal', subtitle: 'GTBank · 07 Nov, 15:36', type: 'Withdrawal', amount: -50000, status: 'Pending' },
  { id: 'DEP-721604', title: 'Salary credit', subtitle: 'Nov payroll · 01 Nov, 08:02', type: 'Deposit', amount: 485000, status: 'Success' },
  { id: 'TXN-610544', title: 'Transfer from Chika', subtitle: 'Eli Digital Bank · 31 Oct, 19:18', type: 'Transfer', amount: 72000, status: 'Success' },
];

export const initialPayouts = [
  { id: 'PO-4901', name: 'Adaeze Okafor', bank: 'Access Bank · 4821', amount: 125000, risk: 'Review', requested: '2 min ago' },
  { id: 'PO-4898', name: 'Tunde Bello', bank: 'GTBank · 1160', amount: 85000, risk: 'High', requested: '18 min ago' },
  { id: 'PO-4895', name: 'Chika Nwosu', bank: 'Zenith · 7004', amount: 42000, risk: 'Review', requested: 'Yesterday' },
  { id: 'PO-4891', name: 'Musa Ibrahim', bank: 'UBA · 9031', amount: 165000, risk: 'High', requested: 'Yesterday' },
];

export const banks = ['Access Bank', 'First Bank', 'GTBank', 'UBA', 'Zenith Bank', 'Stanbic IBTC', 'Fidelity Bank'];

export const money = (value) =>
  new Intl.NumberFormat('en-NG', {
    style: 'currency',
    currency: 'NGN',
    minimumFractionDigits: 2,
  }).format(value);
