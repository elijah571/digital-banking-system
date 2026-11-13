import React, { useRef, useState } from 'react';
import { ArrowRight, Check, Clock3, FileText, Fingerprint, ShieldCheck } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { Field } from '../../components/common/Field';
import { StatusBadge } from '../../components/feedback/StatusBadge';
import { LiveFaceSimulation, readImageBase64 } from '../../components/verification';
import { useVerifyIdentityMutation } from '../../store/api';

export function IdentityVerificationPage({ status, setStatus, showToast }) {
  const [documentType, setDocumentType] = useState('NIN_SLIP');
  const [documentImage, setDocumentImage] = useState(null);
  const [documentError, setDocumentError] = useState('');
  const [faceVerified, setFaceVerified] = useState(false);
  const [selfieImage, setSelfieImage] = useState('');
  const [submitIdentity, identityResult] = useVerifyIdentityMutation();
  const documentInputRef = useRef(null);

  const handleDocumentChange = (event) => {
    const file = event.target.files?.[0];
    setDocumentError('');
    if (!file) {
      setDocumentImage(null);
      return;
    }
    const allowedTypes = ['image/jpeg', 'image/png'];
    const hasAllowedExtension = /\.(jpe?g|png)$/i.test(file.name);
    if ((!allowedTypes.includes(file.type) && !hasAllowedExtension) || file.size > 5 * 1024 * 1024) {
      setDocumentImage(null);
      event.target.value = '';
      setDocumentError(
        file.size > 5 * 1024 * 1024
          ? 'Choose an image smaller than 5 MB.'
          : 'Use a JPG or PNG image.'
      );
      return;
    }
    setDocumentImage(file);
  };

  const handleSubmit = async (event) => {
    event.preventDefault();
    if (!documentImage) {
      setDocumentError('Upload a clear image of your identity document.');
      documentInputRef.current?.focus();
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

    try {
      const result = await submitIdentity({
        documentType,
        documentImage: await readImageBase64(documentImage),
        selfieImage,
        consentGranted: true,
      }).unwrap();

      setStatus?.(result.identityStatus || 'PENDING_REVIEW');
      setDocumentImage(null);
      if (documentInputRef.current) documentInputRef.current.value = '';
      showToast?.('Identity verification request submitted successfully.', 'success');
    } catch (error) {
      showToast?.(
        error?.data?.detail || error?.data?.message || 'Identity verification could not be submitted.',
        'error'
      );
    }
  };

  return (
    <div className="fade-in">
      <PageHeader
        title="Identity verification"
        description="Verify your identity using an approved document and a live facial selfie."
      />

      {['Pending review', 'PENDING_REVIEW', 'VERIFIED'].includes(status) ? (
        <Panel className="mx-auto max-w-3xl p-6 sm:p-8">
          <div className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-gold-50 text-gold-800">
            <Clock3 size={25} />
          </div>
          <div className="mt-4 text-center">
            <StatusBadge tone={status === 'VERIFIED' ? 'green' : 'amber'}>
              {status === 'VERIFIED' ? 'Verified' : 'Pending review'}
            </StatusBadge>
            <h2 className="mt-3 text-xl font-bold text-ink">
              Identity check {status === 'VERIFIED' ? 'complete' : 'submitted'}
            </h2>
            <p className="mx-auto mt-2 max-w-lg text-sm leading-6 text-slate-500">
              Your identity verification request has been received by the verification service and is securely processing.
            </p>
          </div>
          <div className="mt-6 rounded-xl border border-gold-100 bg-gold-50/70 p-4 text-xs leading-5 text-slate-600">
            <b className="text-ink">Privacy note:</b> The selected identity document image and captured selfie are securely sent to the account verification engine with TLS encryption.
          </div>
        </Panel>
      ) : (
        <>
          <Panel className="mb-5 overflow-hidden bg-ink p-5 text-white sm:p-6">
            <div className="flex flex-col gap-5 sm:flex-row sm:items-center sm:justify-between">
              <div className="flex items-start gap-3">
                <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-brand text-ink">
                  <Fingerprint size={19} />
                </span>
                <div>
                  <h2 className="text-sm font-bold">Document & live face verification</h2>
                  <p className="mt-1 max-w-xl text-xs leading-5 text-slate-300">
                    Submit a government-issued ID card and complete a fast selfie verification.
                  </p>
                </div>
              </div>
              <StatusBadge tone="gold">Action needed</StatusBadge>
            </div>
            <div className="mt-5 grid gap-2 text-[11px] text-slate-300 sm:grid-cols-3">
              {['1. Identity document', '2. Live face check', '3. Service review'].map(
                (step, index) => (
                  <div
                    key={step}
                    className={`rounded-lg border px-3 py-2 ${
                      index === 0
                        ? 'border-brand/50 bg-white/10 text-white'
                        : 'border-white/10'
                    }`}
                  >
                    {step}
                  </div>
                )
              )}
            </div>
          </Panel>

          <div className="grid gap-5 lg:grid-cols-[minmax(0,1.35fr)_minmax(260px,0.65fr)]">
            <Panel className="p-5 sm:p-7">
              <div className="mb-5">
                <h2 className="text-sm font-bold text-ink">Identity document</h2>
                <p className="mt-1 text-xs text-slate-500">
                  Select your document type and upload a clear, legible photo of the front.
                </p>
              </div>

              <form onSubmit={handleSubmit} className="space-y-5">
                <Field
                  label="Document type"
                  as="select"
                  value={documentType}
                  onChange={(event) => setDocumentType(event.target.value)}
                  disabled={identityResult.isLoading}
                >
                  <option value="NIN_SLIP">NIN Slip or National ID</option>
                  <option value="PASSPORT">International Passport</option>
                  <option value="DRIVING_LICENSE">Driver's Licence</option>
                  <option value="VOTER_CARD">Voter's Card</option>
                </Field>

                <div>
                  <label
                    htmlFor="identity-document"
                    className="mb-2 block text-[13px] font-semibold text-slate-700"
                  >
                    Identity document image <span className="text-rose-600">*</span>
                  </label>
                  <label
                    htmlFor="identity-document"
                    className="flex cursor-pointer flex-col items-center justify-center rounded-xl border border-dashed border-slate-300 bg-slate-50 px-4 py-6 text-center transition hover:border-brand hover:bg-gold-50/50 focus-within:ring-2 focus-within:ring-brand"
                  >
                    <span className="grid h-10 w-10 place-items-center rounded-xl bg-white text-gold-800 shadow-sm">
                      <FileText size={18} />
                    </span>
                    <span className="mt-3 text-xs font-semibold text-ink">
                      {documentImage ? documentImage.name : 'Choose an identity document image'}
                    </span>
                    <span className="mt-1 text-[10px] text-slate-500">JPG or PNG · up to 5 MB</span>
                    <input
                      ref={documentInputRef}
                      id="identity-document"
                      type="file"
                      accept=".jpg,.jpeg,.png,image/jpeg,image/png"
                      onChange={handleDocumentChange}
                      disabled={identityResult.isLoading}
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
                    disabled={identityResult.isLoading}
                    required
                  />
                  <span>
                    I confirm this identity document belongs to me and consent to identity verification checks.
                  </span>
                </label>

                <Button
                  type="submit"
                  disabled={identityResult.isLoading || !documentImage || !faceVerified}
                  isLoading={identityResult.isLoading}
                  loadingText="Submitting verification…"
                  className="w-full sm:w-auto"
                >
                  Submit identity check <ArrowRight size={15} />
                </Button>
              </form>
            </Panel>

            <div className="space-y-4">
              <Panel className="p-5">
                <div className="flex items-center gap-2">
                  <ShieldCheck size={17} className="text-green-700" />
                  <h3 className="text-sm font-bold text-ink">Your privacy matters</h3>
                </div>
                <p className="mt-3 text-xs leading-5 text-slate-500">
                  Your credentials are never shared. Never provide your PIN, passwords, or transaction OTPs.
                </p>
              </Panel>

              <Panel className="p-5">
                <h3 className="text-sm font-bold text-ink">Before you submit</h3>
                <ul className="mt-4 space-y-3">
                  {[
                    'Use an unexpired government-issued document',
                    'Ensure all corners and text are clear and legible',
                    'Grant camera access for the live selfie scan',
                  ].map((item) => (
                    <li key={item} className="flex gap-2 text-xs text-slate-600">
                      <Check size={14} className="mt-0.5 shrink-0 text-green-700" />
                      {item}
                    </li>
                  ))}
                </ul>
              </Panel>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

export default IdentityVerificationPage;
