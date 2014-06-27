package com.anjlab.android.iab.v3;

public class PurchaseData {

	final static String TAG = "PurchaseData";
	
	public String packageName;
	public String orderId;
	public String productId;
	public String developerPayLoad;
	public String purchaseTime;
	public String purchaseState;
	public String purchaseToken;
	
	public PurchaseData(){}
	public PurchaseData(String packageName, String orderId, String productId, String developerPayLoad,
			String purchaseTime, String purchaseState, String purchaseToken){
		this.packageName = packageName;
		this.orderId = orderId;
		this.productId = productId;
		this.developerPayLoad = developerPayLoad;
		this.purchaseTime = purchaseTime;
		this.purchaseState = purchaseState;
		this.purchaseToken = purchaseToken;
	}
}
