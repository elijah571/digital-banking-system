import React, { useRef, useState } from 'react';
import { ArrowRight, BadgeCheck, Check, Clock3, FileText, ShieldCheck } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { Field } from '../../components/common/Field';
import { StatusBadge } from '../../components/feedback/StatusBadge';
import { LiveFaceSimulation, readImageBase64 } from '../../components/verification';
import { useUpgradeAccountMutation } from '../../store/api';

export function AccountUpgradePage({ status, setStatus, showToast }) {
  const [addressDocumentType, setAddressDocumentType] = useState('UTILITY_BILL');
  const [addressDocument, setAddressDocument] = useState(null);
  const [documentError, setDocumentError] = useState('');
  const [faceVerified, setFaceVerified] = useState(false);
  const [selfieImage, setSelfieImage] = useState('');
  const [submitUpgrade, upgradeResult] = useUpgradeAccountMutation();
  const addressDocumentRef = useRef(null);

  const handleAddressDocumentChange = (event) => {
    const file = event.target.files?.[0];
    setDocumentError('');
    if (!file) {
      setAddressDocument(null);
      return;
    }
    const allowedTypes = ['image/jpeg', 'image/png'];
    const hasAllowedExtension = /\.(jpe?g|png)$/i.test(file.name);
    if ((!allowedTypes.includes(file.type) && !hasAllowedExtension) || file.size > 5 * 1024 * 1024) {
      setAddressDocument(null);
      event.target.value = '';
      setDocumentError(
        file.size > 5 * 1024 * 1024
          ? 'Choose an image smaller than 5 MB.'
          : 'Use a JPG or PNG image.'
      );
      return;
    }
    setAddressDocument(file);
  };

  const handleSubmit = async (event) => {
    event.preventDefault();
    if (!addressDocument) {
      setDocumentError('Upload a proof-of-address image to continue.');
      addressDocumentRef.current?.focus();
      return;
    }
    if (!faceVerified) {
      showToast?.('Capture a selfie with the camera before continuing.', 'error');
      return;
    }
    if (!selfieImage) {
      showToast?.('Run the live face check again so a selfie can be captured.', 'error');
      return;
    }

    const fields = new FormData(event.currentTarget);
    try {
      const result = await submitUpgrade({
        bvn: fields.get('bvn'),
        firstName: fields.get('firstName'),
        lastName: fields.get('lastName'),
        residentialAddress: fields.get('residentialAddress'),
        addressDocumentType,
        addressDocumentImage: await readImageBase64(addressDocument),
        selfieImage,
        consentGranted: true,
      }).unwrap();

      setStatus?.(result.accountUpgradeStatus || 'PENDING_REVIEW');
      setAddressDocument(null);
      if (addressDocumentRef.current) addressDocumentRef.current.value = '';
      showToast?.('Account upgrade request submitted successfully.', 'success');
    } catch (error) {
      showToast?.(
        error?.data?.detail || error?.data?.message || 'Account upgrade could not be submitted.',
        'error'
      );
    }
  };

  return (
    <div className="fade-in">
      <PageHeader
        title="Upgrade your account"
        description="Verify your BVN, residential address, and live face to unlock Tier 3 daily limits."
      />

      {['Pending review', 'PENDING_REVIEW', 'VERIFIED'].includes(status) ? (
        <Panel className="mx-auto max-w-3xl p-6 sm:p-8">
          <div className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-gold-50 text-gold-800">
            <Clock3 size={25} />
          </div>
          <div className="mt-4 text-center">
            <StatusBadge tone={status === 'VERIFIED' ? 'green' : 'amber'}>
              {status === 'VERIFIED' ? 'Tier Upgraded' : 'Pending review'}
            </StatusBadge>
            <h2 className="mt-3 text-xl font-bold text-ink">
              Account upgrade {status === 'VERIFIED' ? 'active' : 'submitted'}
            </h2>
            <p className="mx-auto mt-2 max-w-lg text-sm leading-6 text-slate-500">
              Your tier upgrade verification request was received by the account service and is being validated.
            </p>
          </div>
        </Panel>
      ) : (
        <div className="grid gap-5 lg:grid-cols-[minmax(0,1.3fr)_minmax(260px,0.7fr)]">
          <Panel className="p-5 sm:p-7">
            <div className="mb-6 flex items-start gap-3 rounded-xl bg-slate-50 p-4 border border-slate-100">
              <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-white text-gold-800 shadow-sm">
                <BadgeCheck size={17} />
              </span>
              <div>
                <h2 className="text-sm font-bold text-ink">BVN and address verification</h2>
                <p className="mt-0.5 text-xs leading-5 text-slate-500">
                  Dojah verifies your BVN identity against NIBSS and validates your address proof and liveness.
                </p>
              </div>
            </div>

            <form onSubmit={handleSubmit} className="space-y-5">
              <Field
                label="Bank Verification Number (BVN)"
                name="bvn"
                inputMode="numeric"
                autoComplete="off"
                maxLength={11}
                minLength={11}
                pattern="[0-9]{11}"
                placeholder="Enter your 11-digit BVN"
                hint="Your 11-digit BVN registered with the Central Bank of Nigeria."
                disabled={upgradeResult.isLoading}
                required
              />

              <div className="grid gap-4 sm:grid-cols-2">
                <Field
                  label="First name"
                  name="firstName"
                  autoComplete="given-name"
                  maxLength="80"
                  placeholder="As registered on your BVN"
                  disabled={upgradeResult.isLoading}
                  required
                />
                <Field
                  label="Last name"
                  name="lastName"
                  autoComplete="family-name"
                  maxLength="120"
                  placeholder="As registered on your BVN"
                  disabled={upgradeResult.isLoading}
                  required
                />
              </div>

              <Field
                label="Residential address"
                name="residentialAddress"
                autoComplete="street-address"
                maxLength={500}
                placeholder="House number, street name, city, and state"
                hint="Must match the address shown on your uploaded document."
                disabled={upgradeResult.isLoading}
                required
              />

              <Field
                label="Address document type"
                as="select"
                value={addressDocumentType}
                onChange={(event) => setAddressDocumentType(event.target.value)}
                disabled={upgradeResult.isLoading}
              >
                <option value="UTILITY_BILL">Utility bill (Electricity/Water)</option>
                <option value="BANK_STATEMENT">Bank statement with address</option>
                <option value="LEASE_AGREEMENT">Tenancy or lease agreement</option>
                <option value="GOVERNMENT_LETTER">Official government letter</option>
              </Field>

              <div>
                <label
                  htmlFor="upgrade-address-document"
                  className="mb-2 block text-[13px] font-semibold text-slate-700"
                >
                  Proof of address image <span className="text-rose-600">*</span>
                </label>
                <label
                  htmlFor="upgrade-address-document"
                  className="flex cursor-pointer flex-col items-center justify-center rounded-xl border border-dashed border-slate-300 bg-slate-50 px-4 py-6 text-center transition hover:border-brand hover:bg-gold-50/50 focus-within:ring-2 focus-within:ring-brand"
                >
                  <span className="grid h-10 w-10 place-items-center rounded-xl bg-white text-gold-800 shadow-sm">
                    <FileText size={18} />
                  </span>
                  <span className="mt-3 text-xs font-semibold text-ink">
                    {addressDocument ? addressDocument.name : 'Choose a proof-of-address image'}
                  </span>
                  <span className="mt-1 text-[10px] text-slate-500">JPG or PNG · up to 5 MB</span>
                  <input
                    ref={addressDocumentRef}
                    id="upgrade-address-document"
                    type="file"
                    accept=".jpg,.jpeg,.png,image/jpeg,image/png"
                    onChange={handleAddressDocumentChange}
                    disabled={upgradeResult.isLoading}
                    className="sr-only"
                  />
                </label>
                {documentError && (
                  <p role="alert" className="mt-2 text-xs font-medium text-rose-700">
                    {documentError}
                  </p>
                )}
              </div>

              <LiveFaceSimulation
                verified={faceVerified}
                onVerified={setFaceVerified}
                onSelfie={setSelfieImage}
              />

              <label className="flex items-start gap-2.5 rounded-xl border border-slate-200 p-3.5 text-xs leading-5 text-slate-600">
                <input
                  type="checkbox"
                  className="mt-1 h-4 w-4 shrink-0 accent-brand"
                  disabled={upgradeResult.isLoading}
                  required
                />
                <span>
                  I consent to BVN and document verification. I confirm that all submitted details belong to me.
                </span>
              </label>

              <Button
                type="submit"
                disabled={upgradeResult.isLoading || !addressDocument || !faceVerified}
                isLoading={upgradeResult.isLoading}
                loadingText="Submitting upgrade request…"
                className="w-full sm:w-auto"
              >
                Request account upgrade <ArrowRight size={15} />
              </Button>
            </form>
          </Panel>

          <div className="space-y-4">
            <Panel className="p-5">
              <h3 className="text-sm font-bold text-ink">Upgrade benefits</h3>
              <ul className="mt-4 space-y-3">
                {[
                  'Daily transfer limit increased to ₦5,000,000.00',
                  'Instant verification with NIBSS database',
                  'Priority clearing on outward bank payouts',
                  'All linked savings & current accounts upgraded automatically',
                ].map((item) => (
                  <li key={item} className="flex gap-2 text-xs text-slate-600">
                    <Check size={14} className="mt-0.5 shrink-0 text-green-700" />
                    {item}
                  </li>
                ))}
              </ul>
            </Panel>

            <Panel className="p-5">
              <div className="flex items-center gap-2">
                <ShieldCheck size={17} className="text-green-700" />
                <h3 className="text-sm font-bold text-ink">Protect your BVN</h3>
              </div>
              <p className="mt-3 text-xs leading-5 text-slate-500">
                Only submit your BVN on verified banking platforms. Never disclose your PIN or passwords.
              </p>
            </Panel>
          </div>
        </div>
      )}
    </div>
  );
}

export default AccountUpgradePage;
