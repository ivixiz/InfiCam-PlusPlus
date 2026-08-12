clc;clear;
for i=1:14
A(:,:,i)=xlsread('1-transmittance.xlsx',i);
end
for i=1:14
    A(1,5,i)=1;
    X(1)=0.25;n=2;Y(1)=1;m=0;
    for j=2:64
        if isnan(A(j,5,i))~=1 && A(j,5,i)~=0;
            Y(n)=A(j,5,i);
            X(n)=A(j,2,i)
            n=n+1;
            m=j;
        end
    end
    if n==2;
        A(1:64,5,i)=1;
    else
        A(1:m,5,i) = spline(X,Y,A(1:m,2,i));
        if m<64
        A(m+1:64,5,i) =A(m,5,i);
        end
    end
end
tau(:,:)=A(:,5,:);
tauint=uint16(tau*16384);
f=fopen('tau.bin','wb+');
fwrite(f,tauint,'uint16');fwrite(f,tauint,'uint16');fwrite(f,tauint,'uint16');fwrite(f,tauint,'uint16');
fclose all;
% mesh(tau);
msgbox('convert complate!');