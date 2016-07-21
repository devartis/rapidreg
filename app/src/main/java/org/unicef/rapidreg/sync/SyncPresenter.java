package org.unicef.rapidreg.sync;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hannesdorfmann.mosby.mvp.MvpBasePresenter;
import com.raizlabs.android.dbflow.data.Blob;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.unicef.rapidreg.PrimeroConfiguration;
import org.unicef.rapidreg.event.PullCasesEvent;
import org.unicef.rapidreg.model.Case;
import org.unicef.rapidreg.model.CasePhoto;
import org.unicef.rapidreg.network.SyncService;
import org.unicef.rapidreg.service.CasePhotoService;
import org.unicef.rapidreg.service.CaseService;
import org.unicef.rapidreg.service.RecordService;
import org.unicef.rapidreg.service.cache.ItemValues;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import retrofit2.Response;
import rx.Observable;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

public class SyncPresenter extends MvpBasePresenter<SyncView> {
    private Context context;

    private SyncService syncService;
    private CaseService caseService;
    private CasePhotoService casePhotoService;
    private static final String TAG = SyncPresenter.class.getSimpleName();

    public SyncPresenter(Context context) {
        this.context = context;
        try {
            syncService = new SyncService(context);
            caseService = CaseService.getInstance();
            casePhotoService = CasePhotoService.getInstance();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void attachView(SyncView view) {
        super.attachView(view);
        EventBus.getDefault().register(this);
    }

    @Override
    public void detachView(boolean retainInstance) {
        super.detachView(retainInstance);
        EventBus.getDefault().unregister(this);
    }

    public void tryToSync() {
        if (isViewAttached()) {
            getView().showAttemptSyncDialog();
        }
    }

    public void execSync() {
        if (!isViewAttached()) {
            return;
        }
        try {
            upLoadCases();
        } catch (Exception e) {
            syncFail();
        }
    }

    private void upLoadCases() {
        final List<Case> caseList = CaseService.getInstance().getAll();
        getView().showSyncProgressDialog();
        getView().setProgressMax(caseList.size());

        Observable.just(caseList)
                .flatMap(new Func1<List<Case>, Observable<Case>>() {
                    @Override
                    public Observable<Case> call(List<Case> cases) {
                        return Observable.from(cases);
                    }
                })
                .filter(new Func1<Case, Boolean>() {
                    @Override
                    public Boolean call(Case item) {
                        return !item.isSynced();
                    }
                })
                .concatMap(new Func1<Case, Observable<Response<JsonElement>>>() {
                    @Override
                    public Observable<Response<JsonElement>> call(Case item) {
                        ItemValues values = ItemValues.fromJson(new String(item.getContent().getBlob()));
                        values.addStringItem("case_id", item.getUniqueId());
                        values.addStringItem("short_id", caseService.getShortUUID(item.getUniqueId()));

                        JsonObject jsonObject = new JsonObject();
                        jsonObject.add("child", values.getValues());

                        if (!TextUtils.isEmpty(item.getInternalId())) {
                            return syncService.putCase(PrimeroConfiguration.getCookie(), true, jsonObject);
                        } else {
                            return syncService.postCaseExcludeMediaData(PrimeroConfiguration.getCookie(), true, jsonObject);
                        }
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Action1<Response<JsonElement>>() {
                    @Override
                    public void call(Response<JsonElement> response) {
                        Log.i(TAG, response.toString());
                        getView().setProgressIncrease();

                        JsonObject responseJsonObject = response.body().getAsJsonObject();

                        try {
                            String caseId = responseJsonObject.get("case_id").getAsString();
                            Case item = CaseService.getInstance().getByUniqueId(caseId);
                            if (item != null) {
                                item.setInternalId(responseJsonObject.get("_id").getAsString());
                                item.setInternalRev(responseJsonObject.get("_rev").getAsString());
                                item.setContent(new Blob(responseJsonObject.toString().getBytes()));
                                item.update();

                                uploadCaseMediaData(item);

                                updateSyncStatus(item, true);
                            }
                        } catch (Exception e) {
                            return;
                        }
                    }
                }, new Action1<Throwable>() {
                    @Override
                    public void call(Throwable throwable) {
                        Log.i(TAG, throwable.toString());
                    }
                }, new Action0() {
                    @Override
                    public void call() {
//                        getView().hideSyncProgressDialog();
//                        EventBus.getDefault().post(new PullCasesEvent());
                    }
                });
    }

    private void uploadCaseMediaData(Case aCase) {
        List<CasePhoto> casePhotos = casePhotoService.getByCaseId(aCase.getId());
        for (CasePhoto casePhoto : casePhotos) {

        }

//        syncService.postCaseMediaData()
    }

    void updateSyncStatus(Case item, boolean isSynced) {
        item.setSynced(isSynced);
        item.update();
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    public void pullCases(PullCasesEvent event) {
        getView().showSyncProgressDialog();
        getView().setProgressMax(100);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-mm-dd H:M:S", Locale.US);
        String time = sdf.format(new Date());

        syncService.getCasesIds(PrimeroConfiguration.getCookie(), time, true)
                .flatMap(new Func1<Response<JsonElement>, Observable<JsonElement>>() {
                    @Override
                    public Observable<JsonElement> call(Response<JsonElement> jsonElementResponse) {
                        return null
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Subscriber<JsonElement>() {
                    @Override
                    public void onCompleted() {

                    }

                    @Override
                    public void onError(Throwable e) {

                    }

                    @Override
                    public void onNext(JsonElement jsonElement) {

                    }
                });


        syncService.getAllCasesRx(PrimeroConfiguration.getCookie(), "en", false)
                .filter(new Func1<Response<List<JsonElement>>, Boolean>() {
                    @Override
                    public Boolean call(Response<List<JsonElement>> listResponse) {
                        return listResponse.isSuccessful();
                    }
                })
                .flatMap(new Func1<Response<List<JsonElement>>, Observable<JsonElement>>() {
                    @Override
                    public Observable<JsonElement> call(Response<List<JsonElement>> listResponse) {
                        return Observable.from(listResponse.body());
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread()).subscribe(new Subscriber<JsonElement>() {
            @Override
            public void onCompleted() {
                getView().hideSyncProgressDialog();
                syncSuccessfully();
            }

            @Override
            public void onError(Throwable e) {

            }

            @Override
            public void onNext(JsonElement jsonElement) {
                getView().setProgressIncrease();
                JsonObject jsonObject = jsonElement.getAsJsonObject();
                try {
                    postPullCases(jsonObject);
                } catch (Exception e) {
                    return;
                }
            }
        });
    }

    private void postPullCases(JsonObject casesJsonObject) throws ParseException {
        String caseId = casesJsonObject.get("case_id").getAsString();
        Case item = CaseService.getInstance().getByUniqueId(caseId);
        if (item != null) {
            item.setInternalId(casesJsonObject.get("_id").getAsString());
            item.setInternalRev(casesJsonObject.get("_rev").getAsString());
            item.setSynced(true);
            item.setContent(new Blob(casesJsonObject.toString().getBytes()));
            item.update();
        } else {
            item = new Case();
            item.setUniqueId(CaseService.getInstance().createUniqueId());
            item.setName(casesJsonObject.get("name").getAsString());
            item.setAge(casesJsonObject.get("age").getAsInt());
            item.setInternalId(casesJsonObject.get("_id").getAsString());
            item.setInternalRev(casesJsonObject.get("_rev").getAsString());
            item.setRegistrationDate(
                    RecordService.getRegisterDate(casesJsonObject.get("registration_date").getAsString()));
            item.setCreatedBy(casesJsonObject.get("created_by").getAsString());
            item.setLastSyncedDate(Calendar.getInstance().getTime());
            item.setLastUpdatedDate(Calendar.getInstance().getTime());
            item.setSynced(true);
            item.setContent(new Blob(casesJsonObject.toString().getBytes()));
            item.save();
        }
    }

    private void syncSuccessfully() {
        updateDataViews();
        getView().showSyncSuccessMessage();
        getView().hideSyncProgressDialog();
    }

    private void syncFail() {
        updateDataViews();
        getView().showSyncErrorMessage();
        getView().hideSyncProgressDialog();
    }

    private void updateDataViews() {

    }

    public void attemptCancelSync() {
        getView().showSyncCancelConfirmDialog();
    }

    public void cancelSync() {

    }
}
